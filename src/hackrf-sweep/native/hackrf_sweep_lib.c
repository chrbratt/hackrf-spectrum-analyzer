/*
 * hackrf_sweep_lib.c
 *
 * Library-mode build of upstream hackrf_sweep.c (release 2026.01.3).
 * Original source: https://github.com/greatscottgadgets/hackrf
 *   host/hackrf-tools/src/hackrf_sweep.c
 *
 * The only purpose of this fork is to expose hackrf_sweep_lib_start /
 * hackrf_sweep_lib_stop with __cdecl C linkage so JNA can drive a sweep
 * directly without spawning a child process.
 *
 * Differences vs the upstream tool:
 *   - main()/getopt are compiled out (we get parameters via the
 *     library entry point instead).
 *   - rx_callback delivers FFT bins to a user-supplied callback instead
 *     of writing to outfile.
 *   - We auto-import the FFTW system wisdom file (no -W needed).
 *
 * Original copyright preserved below verbatim.
 *
 * --------------------------------------------------------------------
 * Copyright 2016-2022 Great Scott Gadgets <info@greatscottgadgets.com>
 * Copyright 2016 Dominic Spill <dominicgs@gmail.com>
 * Copyright 2016 Mike Walters <mike@flomp.net>
 *
 * This file is part of HackRF.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; see the file COPYING.  If not, write to
 * the Free Software Foundation, Inc., 51 Franklin Street,
 * Boston, MA 02110-1301, USA.
 * --------------------------------------------------------------------
 */

#define HACKRF_SWEEP_BUILDING_DLL
#include "hackrf_sweep.h"

#include <hackrf.h>
#include <libusb.h>

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <fftw3.h>
#include <inttypes.h>

#define _FILE_OFFSET_BITS 64

#ifdef _WIN32
	#define _USE_MATH_DEFINES
	#include <windows.h>
	#include <io.h>
	#ifdef _MSC_VER
		#ifdef _WIN64
typedef int64_t ssize_t;
		#else
typedef int32_t ssize_t;
		#endif

		#define strtoull _strtoui64
		#define snprintf _snprintf

static int gettimeofday(struct timeval* tv, void* ignored)
{
	FILETIME ft;
	unsigned __int64 tmp = 0;
	(void) ignored;
	if (NULL != tv) {
		GetSystemTimeAsFileTime(&ft);
		tmp |= ft.dwHighDateTime;
		tmp <<= 32;
		tmp |= ft.dwLowDateTime;
		tmp /= 10;
		tmp -= 11644473600000000Ui64;
		tv->tv_sec = (long) (tmp / 1000000UL);
		tv->tv_usec = (long) (tmp % 1000000UL);
	}
	return 0;
}

static void usleep_ms(unsigned int ms)
{
	Sleep(ms);
}
#define usleep(us) usleep_ms((unsigned int)((us) / 1000))
	#endif
#endif

#if defined(__GNUC__)
	#include <unistd.h>
	#include <sys/time.h>
#endif

#include <signal.h>
#include <math.h>

#define FD_BUFFER_SIZE  (8 * 1024)

#define FREQ_ONE_MHZ    (1000000ull)

#define FREQ_MIN_MHZ    (0)
#define FREQ_MAX_MHZ    (7250)

#define DEFAULT_SAMPLE_RATE_HZ            (20000000)
#define DEFAULT_BASEBAND_FILTER_BANDWIDTH (15000000)

#define TUNE_STEP       (DEFAULT_SAMPLE_RATE_HZ / FREQ_ONE_MHZ)
#define OFFSET          7500000

#define BLOCKS_PER_TRANSFER 16

#if defined _WIN32
	#define m_sleep(a) Sleep((a))
#else
	#define m_sleep(a) usleep((a * 1000))
#endif

static uint32_t num_sweeps = 0;
static int num_ranges = 0;
static uint16_t frequencies[MAX_SWEEP_RANGES * 2];
static int step_count;

static float TimevalDiff(const struct timeval* a, const struct timeval* b)
{
	return (a->tv_sec - b->tv_sec) + 1e-6f * (a->tv_usec - b->tv_usec);
}

static volatile bool do_exit = false;

/*
 * outfile is kept as a non-NULL sentinel so the rx_callback NULL check
 * (carried over from upstream) doesn't reject our transfers. We never
 * actually write to it - the library callback consumes the FFT bins.
 */
static FILE* outfile = NULL;
static volatile uint32_t byte_count = 0;
static volatile uint64_t sweep_count = 0;

static struct timeval time_start;
static struct timeval t_start;

static bool amp = false;
static uint32_t amp_enable;

static bool antenna = false;
static uint32_t antenna_enable;

static bool timestamp_normalized = false;
static bool binary_output = false;
static bool ifft_output = false;
static bool one_shot = false;
static bool finite_mode = false;
static volatile bool sweep_started = false;

static int num_fft_bins = 20;
static double fft_bin_width;
static fftwf_complex* fftwIn = NULL;
static fftwf_complex* fftwOut = NULL;
static fftwf_plan fftwPlan = NULL;
static fftwf_complex* ifftwIn = NULL;
static fftwf_complex* ifftwOut = NULL;
static fftwf_plan ifftwPlan = NULL;
static uint32_t ifft_idx = 0;
static float* pwr;
static float* window;

static struct timeval usb_transfer_time;

/* Library callback + bin accumulators (replaces fwrite to outfile). */
static void (*fft_power_callback)(
	char     full_sweep_done,
	int      bins,
	double*  freqStart,
	float    fft_bin_Hz,
	float*   powerdBm);
static double* binsFreqStart   = NULL;
static float*  binsPowerdBm    = NULL;
static int     binsMaxEntries  = 0;

static float logPower(fftwf_complex in, float scale)
{
	float re = in[0] * scale;
	float im = in[1] * scale;
	float magsq = re * re + im * im;
	return (float) (log2(magsq) * 10.0f / log2(10.0f));
}

static int rx_callback(hackrf_transfer* transfer)
{
	int8_t* buf;
	uint8_t* ubuf;
	uint64_t frequency;
	int i, j, ifft_bins;

	int  binsLength      = 0;
	bool fullSweepDone   = false;
	bool stopProcessing  = false;

	if (NULL == outfile) {
		return -1;
	}

	if (do_exit) {
		return 0;
	}

	if ((usb_transfer_time.tv_sec == 0 && usb_transfer_time.tv_usec == 0) ||
	    timestamp_normalized == false) {
		gettimeofday(&usb_transfer_time, NULL);
	}

	byte_count += transfer->valid_length;
	buf = (int8_t*) transfer->buffer;
	ifft_bins = num_fft_bins * step_count;
	for (j = 0; j < BLOCKS_PER_TRANSFER; j++) {
		ubuf = (uint8_t*) buf;
		if (ubuf[0] == 0x7F && ubuf[1] == 0x7F) {
			frequency = ((uint64_t) (ubuf[9]) << 56) |
				((uint64_t) (ubuf[8]) << 48) |
				((uint64_t) (ubuf[7]) << 40) |
				((uint64_t) (ubuf[6]) << 32) |
				((uint64_t) (ubuf[5]) << 24) |
				((uint64_t) (ubuf[4]) << 16) |
				((uint64_t) (ubuf[3]) << 8) | ubuf[2];
		} else {
			buf += BYTES_PER_BLOCK;
			continue;
		}
		if (frequency == (uint64_t) (FREQ_ONE_MHZ * frequencies[0])) {
			/*
			 * Crossed back to the bottom of the configured range.
			 * Flush whatever bins we accumulated for the previous
			 * sweep so the GUI can repaint.
			 */
			fullSweepDone = true;
			fft_power_callback(
				fullSweepDone,
				binsLength,
				binsFreqStart,
				(float) fft_bin_width,
				binsPowerdBm);
			binsLength = 0;
			fullSweepDone = false;

			if (sweep_started) {
				if (ifft_output) {
					fftwf_execute(ifftwPlan);
				}
				sweep_count++;

				if (timestamp_normalized == true) {
					gettimeofday(&usb_transfer_time, NULL);
				}

				if (one_shot) {
					do_exit = true;
				} else if (finite_mode &&
				           sweep_count == num_sweeps) {
					do_exit = true;
				}
			}
			sweep_started = true;
		}
		if (do_exit) {
			return 0;
		}
		if (!sweep_started) {
			buf += BYTES_PER_BLOCK;
			continue;
		}
		if ((FREQ_MAX_MHZ * FREQ_ONE_MHZ) < frequency) {
			buf += BYTES_PER_BLOCK;
			continue;
		}
		buf += BYTES_PER_BLOCK - (num_fft_bins * 2);
		for (i = 0; i < num_fft_bins; i++) {
			fftwIn[i][0] = buf[i * 2] * window[i] * 1.0f / 128.0f;
			fftwIn[i][1] = buf[i * 2 + 1] * window[i] * 1.0f / 128.0f;
		}
		buf += num_fft_bins * 2;
		fftwf_execute(fftwPlan);
		for (i = 0; i < num_fft_bins; i++) {
			pwr[i] = logPower(fftwOut[i], 1.0f / num_fft_bins);
		}
		if (binary_output) {
			/*
			 * Two halves of the sweep block, just like upstream's
			 * binary_output writes. We push them into the bin
			 * buffer instead of fwrite-ing them.
			 */
			if (!stopProcessing) {
				for (i = 0; i < num_fft_bins / 4; ++i) {
					if (binsLength >= binsMaxEntries) {
						fprintf(stderr,
							"binsLength %d > binsMaxEntries %d\n",
							binsLength,
							binsMaxEntries);
						stopProcessing = true;
						break;
					}
					binsFreqStart[binsLength] =
						frequency + i * (double) fft_bin_width;
					binsPowerdBm[binsLength] =
						pwr[1 + (num_fft_bins * 5) / 8 + i];
					binsLength++;
				}
			}
			if (!stopProcessing) {
				for (i = 0; i < num_fft_bins / 4; ++i) {
					if (binsLength >= binsMaxEntries) {
						fprintf(stderr,
							"binsLength %d > binsMaxEntries %d\n",
							binsLength,
							binsMaxEntries);
						stopProcessing = true;
						break;
					}
					binsFreqStart[binsLength] = frequency +
						DEFAULT_SAMPLE_RATE_HZ / 2 +
						i * (double) fft_bin_width;
					binsPowerdBm[binsLength] =
						pwr[1 + num_fft_bins / 8 + i];
					binsLength++;
				}
			}
		}
	}

	if (binsLength > 0) {
		fft_power_callback(
			fullSweepDone,
			binsLength,
			binsFreqStart,
			(float) fft_bin_width,
			binsPowerdBm);
	}

	return 0;
}

static hackrf_device* device = NULL;

/* ------------------------------------------------------------------ */
/* Device metadata helpers (used by list_devices + get_opened_info).  */
/* ------------------------------------------------------------------ */

/* Cached info about the device currently held open by start().
 * Cleared on stop / on any open failure. */
static hackrf_sweep_device_info_t opened_info;
static bool opened_info_valid = false;

/*
 * Compose a friendly label for the device given what we know.
 *
 * Pre-open we only have the USB board id, so "HackRF One"/"Jawbreaker"/
 * "rad1o". After hackrf_board_id_read() succeeds we also have the
 * firmware id, which distinguishes "HackRF One OG" from "HackRF One r9"
 * and from "Praline (HackRF Pro)".
 */
static void compose_board_name(
	char* out,
	size_t out_size,
	enum hackrf_usb_board_id usb_id,
	int firmware_id /* -1 if not yet read */)
{
	const char* usb_name = hackrf_usb_board_id_name(usb_id);
	if (!usb_name) {
		usb_name = "Unknown";
	}

	if (firmware_id < 0) {
		snprintf(out, out_size, "%s", usb_name);
		return;
	}

	const char* refined = NULL;
	switch ((enum hackrf_board_id) firmware_id) {
	case BOARD_ID_PRALINE:
		refined = "Praline (HackRF Pro)";
		break;
	case BOARD_ID_HACKRF1_R9:
		refined = "HackRF One r9";
		break;
	case BOARD_ID_HACKRF1_OG:
		refined = "HackRF One OG";
		break;
	case BOARD_ID_JAWBREAKER:
		refined = "Jawbreaker";
		break;
	case BOARD_ID_RAD1O:
		refined = "rad1o";
		break;
	default:
		break;
	}

	if (refined) {
		snprintf(out, out_size, "%s", refined);
	} else {
		snprintf(out, out_size, "%s (board id %d)", usb_name, firmware_id);
	}
}

static void copy_serial(char* out, size_t out_size, const char* src)
{
	if (out_size == 0) {
		return;
	}
	if (src == NULL) {
		out[0] = '\0';
		return;
	}
	size_t n = strlen(src);
	if (n >= out_size) {
		n = out_size - 1;
	}
	memcpy(out, src, n);
	out[n] = '\0';
}

int HSCALL hackrf_sweep_lib_list_devices(
	hackrf_sweep_device_info_t* out_entries,
	int max_entries)
{
	if (max_entries < 0) {
		return -1;
	}

	int init_result = hackrf_init();
	if (init_result != HACKRF_SUCCESS) {
		fprintf(stderr,
			"hackrf_sweep_lib_list_devices: hackrf_init() failed: "
			"%s (%d)\n",
			hackrf_error_name(init_result),
			init_result);
		return -1;
	}

	hackrf_device_list_t* list = hackrf_device_list();
	if (!list) {
		hackrf_exit();
		return -1;
	}

	int total = list->devicecount;
	int copy_count = total < max_entries ? total : max_entries;
	for (int i = 0; i < copy_count && out_entries; i++) {
		hackrf_sweep_device_info_t* slot = &out_entries[i];
		memset(slot, 0, sizeof(*slot));
		copy_serial(slot->serial, sizeof(slot->serial),
			list->serial_numbers ? list->serial_numbers[i] : NULL);
		enum hackrf_usb_board_id usb_id = list->usb_board_ids
			? list->usb_board_ids[i]
			: USB_BOARD_ID_INVALID;
		slot->usb_board_id = (uint32_t) usb_id;
		slot->board_id = HACKRF_SWEEP_BOARD_ID_UNKNOWN;
		compose_board_name(
			slot->board_name,
			sizeof(slot->board_name),
			usb_id,
			-1);
	}

	hackrf_device_list_free(list);
	hackrf_exit();
	return total;
}

int HSCALL hackrf_sweep_lib_get_opened_info(
	hackrf_sweep_device_info_t* out_info)
{
	if (!out_info) {
		return 0;
	}
	if (!opened_info_valid) {
		memset(out_info, 0, sizeof(*out_info));
		return 0;
	}
	*out_info = opened_info;
	return 1;
}

void HSCALL hackrf_sweep_lib_stop(void)
{
	do_exit = true;
}

int HSCALL hackrf_sweep_lib_start(
	void (*_fft_power_callback)(
		char     full_sweep_done,
		int      bins,
		double*  freqStart,
		float    fft_bin_Hz,
		float*   powerdBm),
	uint32_t freq_min,
	uint32_t freq_max,
	uint32_t _fft_bin_width,
	uint32_t num_samples,
	unsigned int lna_gain,
	unsigned int vga_gain,
	unsigned int _antennaPowerEnable,
	unsigned int _enableAntennaLNA,
	const char*  serial)
{
	/* Legacy single-range entry point: forward to the multi-range
	 * implementation with a one-element pair array. Keeps existing JNA
	 * callers working without recompilation. */
	uint16_t pair[2];
	pair[0] = (uint16_t) freq_min;
	pair[1] = (uint16_t) freq_max;
	return hackrf_sweep_lib_start_multi(
		_fft_power_callback,
		1,
		pair,
		_fft_bin_width,
		num_samples,
		lna_gain,
		vga_gain,
		_antennaPowerEnable,
		_enableAntennaLNA,
		serial);
}

int HSCALL hackrf_sweep_lib_start_multi(
	void (*_fft_power_callback)(
		char     full_sweep_done,
		int      bins,
		double*  freqStart,
		float    fft_bin_Hz,
		float*   powerdBm),
	int          _num_ranges,
	const uint16_t* range_pairs,
	uint32_t _fft_bin_width,
	uint32_t num_samples,
	unsigned int lna_gain,
	unsigned int vga_gain,
	unsigned int _antennaPowerEnable,
	unsigned int _enableAntennaLNA,
	const char*  serial)
{
	int i, result = 0;
	int exit_code = EXIT_SUCCESS;
	struct timeval time_now;
	struct timeval time_prev;
	float time_diff;
	float sweep_rate = 0;

	(void) time_start;

	do_exit       = false;
	sweep_count   = 0;
	byte_count    = 0;
	sweep_started = false;
	num_ranges    = 0;
	binary_output = true;
	ifft_output   = false;
	one_shot      = false;
	finite_mode   = false;
	timestamp_normalized = false;
	num_sweeps    = 0;

	setbuf(stderr, NULL);

	if (_fft_power_callback == NULL) {
		fprintf(stderr, "argument error: callback function pointer NULL\n");
		return EXIT_FAILURE;
	}
	fft_power_callback = _fft_power_callback;

	if (_num_ranges < 1 || _num_ranges > MAX_SWEEP_RANGES) {
		fprintf(stderr,
			"argument error: num_ranges must be between 1 and %u.\n",
			MAX_SWEEP_RANGES);
		return EXIT_FAILURE;
	}
	if (range_pairs == NULL) {
		fprintf(stderr, "argument error: range_pairs pointer NULL.\n");
		return EXIT_FAILURE;
	}

	/* Validate every pair before touching device state. Bail out early on
	 * the first error so the caller gets a single, deterministic message
	 * rather than a half-configured sweep. Ranges must be ascending and
	 * non-overlapping (libhackrf requires this for hackrf_init_sweep). */
	for (i = 0; i < _num_ranges; i++) {
		uint16_t lo = range_pairs[2 * i];
		uint16_t hi = range_pairs[2 * i + 1];
		if (lo >= hi) {
			fprintf(stderr,
				"argument error: range %d: end (%u) must be greater than start (%u).\n",
				i, hi, lo);
			return EXIT_FAILURE;
		}
		if (FREQ_MAX_MHZ < hi) {
			fprintf(stderr,
				"argument error: range %d end (%u) above %u MHz.\n",
				i, hi, FREQ_MAX_MHZ);
			return EXIT_FAILURE;
		}
		if (i > 0 && lo < range_pairs[2 * (i - 1) + 1]) {
			fprintf(stderr,
				"argument error: range %d (%u..%u) overlaps previous range (%u..%u).\n",
				i, lo, hi,
				range_pairs[2 * (i - 1)], range_pairs[2 * (i - 1) + 1]);
			return EXIT_FAILURE;
		}
		frequencies[2 * num_ranges]     = lo;
		frequencies[2 * num_ranges + 1] = hi;
		num_ranges++;
	}

	(void) num_samples; /* upstream constant - kept for ABI compat */
	num_fft_bins = DEFAULT_SAMPLE_RATE_HZ / _fft_bin_width;

	antenna        = true;
	antenna_enable = !!_antennaPowerEnable;
	amp            = true;
	amp_enable     = !!_enableAntennaLNA;

	/* Free wisdom-based plan import - safe even if no wisdom is installed. */
	fftwf_import_system_wisdom();

	if (lna_gain % 8) {
		fprintf(stderr, "warning: lna_gain (-l) must be a multiple of 8\n");
	}
	if (vga_gain % 2) {
		fprintf(stderr, "warning: vga_gain (-g) must be a multiple of 2\n");
	}

	if (4 > num_fft_bins) {
		fprintf(stderr,
			"argument error: FFT bin width must be no more than 5000000\n");
		return EXIT_FAILURE;
	}
	if (8180 < num_fft_bins) {
		fprintf(stderr,
			"argument error: FFT bin width must be no less than 2445\n");
		return EXIT_FAILURE;
	}
	while ((num_fft_bins + 4) % 8) {
		num_fft_bins++;
	}

	fft_bin_width = (double) DEFAULT_SAMPLE_RATE_HZ / num_fft_bins;
	fftwIn  = (fftwf_complex*) fftwf_malloc(sizeof(fftwf_complex) * num_fft_bins);
	fftwOut = (fftwf_complex*) fftwf_malloc(sizeof(fftwf_complex) * num_fft_bins);
	fftwPlan = fftwf_plan_dft_1d(
		num_fft_bins, fftwIn, fftwOut, FFTW_FORWARD, FFTW_MEASURE);
	pwr    = (float*) fftwf_malloc(sizeof(float) * num_fft_bins);
	window = (float*) fftwf_malloc(sizeof(float) * num_fft_bins);
	for (i = 0; i < num_fft_bins; i++) {
		window[i] = (float) (0.5f *
			(1.0f - cos(2 * M_PI * i / (num_fft_bins - 1))));
	}

	/* Warm the plan once before real samples arrive (issue #1366). */
	fftwf_execute(fftwPlan);

	memset(&usb_transfer_time, 0, sizeof(usb_transfer_time));

	binsMaxEntries = num_fft_bins / 4 * 2 * BLOCKS_PER_TRANSFER;
	binsFreqStart  = (double*) malloc(sizeof(*binsFreqStart) * binsMaxEntries);
	binsPowerdBm   = (float*)  malloc(sizeof(*binsPowerdBm)  * binsMaxEntries);
	if (!binsFreqStart || !binsPowerdBm) {
		free(binsFreqStart);
		free(binsPowerdBm);
		binsFreqStart = NULL;
		binsPowerdBm  = NULL;
		fprintf(stderr, "cannot allocate bin buffers\n");
		return EXIT_FAILURE;
	}

	/*
	 * Reset the device through libusb before opening it via libhackrf.
	 * Avoids a stuck-hardware state if a previous sweep didn't shut
	 * down cleanly. Best-effort - we ignore errors here.
	 */
	if (libusb_init(NULL) == 0) {
		const uint16_t hackrf_usb_vid = 0x1d50;
		/* HackRF One PID also covers HackRF Pro in legacy mode. */
		const uint16_t hackrf_one_usb_pid = 0x6089;
		struct libusb_device_handle* usb_device =
			libusb_open_device_with_vid_pid(NULL,
				hackrf_usb_vid,
				hackrf_one_usb_pid);
		if (usb_device) {
			libusb_reset_device(usb_device);
			libusb_close(usb_device);

			int timeout = 100;
			while (timeout-- > 0) {
				usb_device = libusb_open_device_with_vid_pid(
					NULL,
					hackrf_usb_vid,
					hackrf_one_usb_pid);
				if (usb_device != NULL) {
					libusb_close(usb_device);
					break;
				}
				usleep(10000);
			}
		}
	}

	result = hackrf_init();
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr,
			"hackrf_init() failed: %s (%d)\n",
			hackrf_error_name(result),
			result);
		return EXIT_FAILURE;
	}

	/* Empty string means "first available" too, mirroring how the Java
	 * side defaults to "" when no specific device has been picked. */
	const char* desired_serial = (serial && serial[0] != '\0') ? serial : NULL;
	result = hackrf_open_by_serial(desired_serial, &device);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr,
			"hackrf_open() failed: %s (%d)%s%s\n",
			hackrf_error_name(result),
			result,
			desired_serial ? " for serial " : "",
			desired_serial ? desired_serial : "");
		opened_info_valid = false;
		return EXIT_FAILURE;
	}

	/* Cache device metadata so the UI can show what was actually opened.
	 * board_id_read can fail on hot-replug edge cases; in that case we
	 * still publish the serial and USB id with board_id = UNKNOWN. */
	{
		uint8_t board_id_raw = (uint8_t) HACKRF_SWEEP_BOARD_ID_UNKNOWN;
		int board_id_result = hackrf_board_id_read(device, &board_id_raw);
		int firmware_id = (board_id_result == HACKRF_SUCCESS)
			? (int) board_id_raw
			: -1;

		memset(&opened_info, 0, sizeof(opened_info));
		copy_serial(opened_info.serial,
			sizeof(opened_info.serial),
			desired_serial);
		/* If the user passed NULL we don't yet know the serial of the
		 * device libhackrf picked. Leave it empty - get_opened_info()
		 * will still report the board name correctly. */
		opened_info.usb_board_id = (uint32_t) USB_BOARD_ID_HACKRF_ONE;
		opened_info.board_id = (firmware_id < 0)
			? HACKRF_SWEEP_BOARD_ID_UNKNOWN
			: (uint32_t) firmware_id;
		compose_board_name(
			opened_info.board_name,
			sizeof(opened_info.board_name),
			USB_BOARD_ID_HACKRF_ONE,
			firmware_id);
		opened_info_valid = true;
	}

	outfile = stdout; /* sentinel only - rx_callback never writes */

	result = setvbuf(outfile, NULL, _IOFBF, FD_BUFFER_SIZE);
	if (result != 0) {
		fprintf(stderr, "setvbuf() failed: %d\n", result);
	}

	fprintf(stderr,
		"call hackrf_sample_rate_set(%.03f MHz)\n",
		((float) DEFAULT_SAMPLE_RATE_HZ / (float) FREQ_ONE_MHZ));
	result = hackrf_set_sample_rate_manual(device, DEFAULT_SAMPLE_RATE_HZ, 1);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr,
			"hackrf_sample_rate_set() failed: %s (%d)\n",
			hackrf_error_name(result),
			result);
		return EXIT_FAILURE;
	}

	fprintf(stderr,
		"call hackrf_baseband_filter_bandwidth_set(%.03f MHz)\n",
		((float) DEFAULT_BASEBAND_FILTER_BANDWIDTH / (float) FREQ_ONE_MHZ));
	result = hackrf_set_baseband_filter_bandwidth(
		device,
		DEFAULT_BASEBAND_FILTER_BANDWIDTH);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr,
			"hackrf_baseband_filter_bandwidth_set() failed: %s (%d)\n",
			hackrf_error_name(result),
			result);
		return EXIT_FAILURE;
	}

	result  = hackrf_set_vga_gain(device, vga_gain);
	result |= hackrf_set_lna_gain(device, lna_gain);

	for (i = 0; i < num_ranges; i++) {
		step_count = 1 +
			(frequencies[2 * i + 1] - frequencies[2 * i] - 1) /
				TUNE_STEP;
		frequencies[2 * i + 1] = (uint16_t) (frequencies[2 * i] +
			step_count * TUNE_STEP);
		fprintf(stderr,
			"Sweeping from %u MHz to %u MHz\n",
			frequencies[2 * i],
			frequencies[2 * i + 1]);
	}

	result = hackrf_init_sweep(
		device,
		frequencies,
		num_ranges,
		BYTES_PER_BLOCK,
		TUNE_STEP * FREQ_ONE_MHZ,
		OFFSET,
		INTERLEAVED);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr,
			"hackrf_init_sweep() failed: %s (%d)\n",
			hackrf_error_name(result),
			result);
		return EXIT_FAILURE;
	}

	result |= hackrf_start_rx_sweep(device, rx_callback, NULL);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr,
			"hackrf_start_rx_sweep() failed: %s (%d)\n",
			hackrf_error_name(result),
			result);
		return EXIT_FAILURE;
	}

	if (amp) {
		fprintf(stderr, "call hackrf_set_amp_enable(%u)\n", amp_enable);
		result = hackrf_set_amp_enable(device, (uint8_t) amp_enable);
		if (result != HACKRF_SUCCESS) {
			fprintf(stderr,
				"hackrf_set_amp_enable() failed: %s (%d)\n",
				hackrf_error_name(result),
				result);
			return EXIT_FAILURE;
		}
	}
	if (antenna) {
		fprintf(stderr, "call hackrf_set_antenna_enable(%u)\n",
			antenna_enable);
		result = hackrf_set_antenna_enable(device,
			(uint8_t) antenna_enable);
		if (result != HACKRF_SUCCESS) {
			fprintf(stderr,
				"hackrf_set_antenna_enable() failed: %s (%d)\n",
				hackrf_error_name(result),
				result);
			return EXIT_FAILURE;
		}
	}

	gettimeofday(&t_start, NULL);
	time_prev = t_start;

	fprintf(stderr, "Stop with hackrf_sweep_lib_stop()\n");
	while ((hackrf_is_streaming(device) == HACKRF_TRUE) &&
	       (do_exit == false)) {
		float time_difference;

		/* Poll do_exit every 10 ms so stop() responds within ~10 ms. */
		int limit = 20 * 10;
		while (do_exit == false && limit-- > 0) {
			usleep(10000);
		}
		if (do_exit) {
			break;
		}

		gettimeofday(&time_now, NULL);
		if (TimevalDiff(&time_now, &time_prev) >= 1.0f) {
			time_difference = TimevalDiff(&time_now, &t_start);
			sweep_rate = (float) sweep_count / time_difference;
			fprintf(stderr,
				"%" PRIu64
				" total sweeps completed, %.2f sweeps/second\n",
				sweep_count,
				sweep_rate);

			if (byte_count == 0) {
				exit_code = EXIT_FAILURE;
				fprintf(stderr,
					"\nCouldn't transfer any data for one second.\n");
				break;
			}
			byte_count = 0;
			time_prev = time_now;
		}
	}

	result = hackrf_is_streaming(device);
	if (do_exit) {
		fprintf(stderr, "\nExiting...\n");
	} else {
		fprintf(stderr,
			"\nExiting... hackrf_is_streaming() result: %s (%d)\n",
			hackrf_error_name(result),
			result);
	}

	gettimeofday(&time_now, NULL);
	time_diff = TimevalDiff(&time_now, &t_start);
	if ((sweep_rate == 0) && (time_diff > 0)) {
		sweep_rate = sweep_count / time_diff;
	}
	fprintf(stderr,
		"Total sweeps: %" PRIu64 " in %.5f seconds (%.2f sweeps/second)\n",
		sweep_count,
		time_diff,
		sweep_rate);

	if (device != NULL) {
		result = hackrf_close(device);
		if (result != HACKRF_SUCCESS) {
			fprintf(stderr,
				"hackrf_close() failed: %s (%d)\n",
				hackrf_error_name(result),
				result);
		} else {
			fprintf(stderr, "hackrf_close() done\n");
		}
		hackrf_exit();
		fprintf(stderr, "hackrf_exit() done\n");
		device = NULL;
		opened_info_valid = false;
	}

	outfile = NULL;

	if (fftwIn)   { fftwf_free(fftwIn);   fftwIn = NULL; }
	if (fftwOut)  { fftwf_free(fftwOut);  fftwOut = NULL; }
	if (pwr)      { fftwf_free(pwr);      pwr = NULL; }
	if (window)   { fftwf_free(window);   window = NULL; }
	if (ifftwIn)  { fftwf_free(ifftwIn);  ifftwIn = NULL; }
	if (ifftwOut) { fftwf_free(ifftwOut); ifftwOut = NULL; }

	free(binsFreqStart);
	free(binsPowerdBm);
	binsFreqStart = NULL;
	binsPowerdBm = NULL;

	fprintf(stderr, "exit\n");
	return exit_code;
}

/*
 * Public C ABI for the hackrf-sweep library wrapper.
 *
 * Exported entry points (plain __cdecl, callable via JNA):
 *   - hackrf_sweep_lib_start:           blocks the caller's thread until stop().
 *   - hackrf_sweep_lib_stop:            request orderly shutdown from any thread.
 *   - hackrf_sweep_lib_list_devices:    enumerate connected HackRF devices.
 *   - hackrf_sweep_lib_get_opened_info: query the device currently held by start().
 *
 * Only ONE concurrent sweep is supported (matches the upstream
 * hackrf_sweep tool, which uses file-scope globals).
 *
 * Wire format note: hackrf_sweep_device_info_t uses 32-bit fields and char
 * arrays sized to multiples of 4 so JNA can mirror the layout with a plain
 * @FieldOrder annotation - no compiler-specific padding to worry about.
 */

#ifndef HACKRF_SWEEP_H_
#define HACKRF_SWEEP_H_

#include <stdint.h>

#ifdef _WIN32
  #ifdef HACKRF_SWEEP_BUILDING_DLL
    #define HSAPI __declspec(dllexport)
  #else
    #define HSAPI __declspec(dllimport)
  #endif
  #define HSCALL __cdecl
#else
  #define HSAPI
  #define HSCALL
#endif

/* Sentinel value for hackrf_sweep_device_info_t.board_id when the firmware
 * board id is unknown (e.g. before the device has been opened).
 * Matches libhackrf's BOARD_ID_UNDETECTED (0xFF). */
#define HACKRF_SWEEP_BOARD_ID_UNKNOWN 0xFFu

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Per-device metadata returned by hackrf_sweep_lib_list_devices and
 * hackrf_sweep_lib_get_opened_info.
 *
 * Total size is 112 bytes; layout is fully deterministic across MSVC, GCC
 * and Clang because every field is naturally aligned to 4 bytes.
 */
typedef struct {
    /* Lower-case hex ASCII, NUL-terminated. libhackrf serials are 32 chars. */
    char     serial[40];
    /* Human-readable composed name, e.g. "HackRF One", "Praline (HackRF Pro)".
     * NUL-terminated. Filled with the best label available given what we know
     * (USB board id pre-open, refined to firmware board id post-open). */
    char     board_name[64];
    /* enum hackrf_usb_board_id (USB descriptor). 0x6089 = HACKRF_ONE,
     * which is also what HackRF Pro reports on the USB layer. */
    uint32_t usb_board_id;
    /* enum hackrf_board_id (firmware id). 5 = PRALINE = HackRF Pro,
     * 4 = HACKRF1_R9, 2 = HACKRF1_OG. HACKRF_SWEEP_BOARD_ID_UNKNOWN until
     * the device has been opened (i.e. only get_opened_info populates this). */
    uint32_t board_id;
} hackrf_sweep_device_info_t;

/**
 * Enumerate every HackRF currently visible to libusb.
 *
 * Writes up to max_entries into out_entries (which may be NULL if max_entries
 * is 0, useful for sizing). Returns the total number of devices present so
 * the caller can detect truncation, or -1 on libhackrf failure.
 *
 * Safe to call while a sweep is running.
 */
HSAPI int HSCALL hackrf_sweep_lib_list_devices(
    hackrf_sweep_device_info_t* out_entries,
    int max_entries);

/**
 * Fill out_info with the device currently opened by hackrf_sweep_lib_start.
 *
 * Returns 1 if a device is open (out_info is fully populated, including the
 * post-open board_id), 0 otherwise (out_info is zeroed).
 */
HSAPI int HSCALL hackrf_sweep_lib_get_opened_info(
    hackrf_sweep_device_info_t* out_info);

/**
 * Start a blocking sweep. Returns when hackrf_sweep_lib_stop() is called
 * or the device stops streaming.
 *
 * lna_gain must be a multiple of 8 (0-40), vga_gain a multiple of 2 (0-62).
 *
 * @param serial NUL-terminated serial of the device to open, or NULL / ""
 *               for "first available" (preserves the legacy behaviour).
 *               Matches libhackrf hackrf_open_by_serial semantics.
 */
HSAPI int HSCALL hackrf_sweep_lib_start(
    void (*fft_power_callback)(
        char     full_sweep_done,
        int      bins,
        double*  freqStartHz,
        float    fft_bin_Hz,
        float*   powerdBm),
    uint32_t freq_min_mhz,
    uint32_t freq_max_mhz,
    uint32_t fft_bin_width_hz,
    uint32_t num_samples,
    unsigned int lna_gain,
    unsigned int vga_gain,
    unsigned int antenna_power_enable,
    unsigned int rf_amp_enable,
    const char*  serial);

/**
 * Multi-range version of hackrf_sweep_lib_start: sweep up to MAX_SWEEP_RANGES
 * non-overlapping (start_mhz, end_mhz) pairs in a single hackrf_init_sweep
 * call. The native callback delivers samples for all ranges; chart code
 * concatenates them via {@code FrequencyPlan} on the Java side.
 *
 * @param num_ranges    1..MAX_SWEEP_RANGES
 * @param range_pairs   array of {@code 2 * num_ranges} uint16 values laid out
 *                      as {@code [start0, end0, start1, end1, ...]} in MHz,
 *                      ascending and non-overlapping. Caller owns the buffer;
 *                      the function copies it before returning.
 *
 * Falling back to hackrf_sweep_lib_start with num_ranges == 1 is equivalent
 * to calling this with a single pair.
 */
HSAPI int HSCALL hackrf_sweep_lib_start_multi(
    void (*fft_power_callback)(
        char     full_sweep_done,
        int      bins,
        double*  freqStartHz,
        float    fft_bin_Hz,
        float*   powerdBm),
    int          num_ranges,
    const uint16_t* range_pairs,
    uint32_t fft_bin_width_hz,
    uint32_t num_samples,
    unsigned int lna_gain,
    unsigned int vga_gain,
    unsigned int antenna_power_enable,
    unsigned int rf_amp_enable,
    const char*  serial);

HSAPI void HSCALL hackrf_sweep_lib_stop(void);

#ifdef __cplusplus
}
#endif

#endif /* HACKRF_SWEEP_H_ */

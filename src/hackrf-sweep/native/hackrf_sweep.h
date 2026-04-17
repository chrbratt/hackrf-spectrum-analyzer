/*
 * Public C ABI for the hackrf-sweep library wrapper.
 *
 * Two exported functions, plain __cdecl, callable via JNA:
 *   - hackrf_sweep_lib_start: blocks the caller's thread until stop().
 *   - hackrf_sweep_lib_stop:  request orderly shutdown from any thread.
 *
 * Only ONE concurrent sweep is supported (matches the upstream
 * hackrf_sweep tool, which uses file-scope globals).
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

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Start a blocking sweep. Returns when hackrf_sweep_lib_stop() is called
 * or the device stops streaming.
 *
 * Pass 0 for num_samples to use the upstream default. lna_gain must be a
 * multiple of 8 (0-40), vga_gain a multiple of 2 (0-62), to match what
 * the HackRF Pro hardware actually accepts.
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
    unsigned int rf_amp_enable);

HSAPI void HSCALL hackrf_sweep_lib_stop(void);

#ifdef __cplusplus
}
#endif

#endif /* HACKRF_SWEEP_H_ */

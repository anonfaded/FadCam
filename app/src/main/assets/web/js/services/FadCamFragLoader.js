/**
 * FadCamFragLoader — HLS.js custom fragment loader with AES-256-GCM decryption.
 *
 * Intercepts HLS media segment (*.m4s) downloads and transparently decrypts
 * FadCam E2E-encrypted payloads before passing the cleartext to HLS.js.
 *
 * Wire format expected for every media segment:
 *   [ magic 4 B: 0xFADCE245 ][ nonce 12 B ][ AES-256-GCM ciphertext + 16-B auth tag ]
 *
 * The init.mp4 initialization segment is passed through unmodified (never encrypted).
 *
 * Requirements:
 *  - window.E2EKeyManager must be loaded before this script.
 *  - window.FadCamRemote must be available when a stream is active.
 *  - HLS.js must be loaded before this script (uses Hls.DefaultConfig.loader).
 */
(function () {
  'use strict';

  const MAGIC_BYTES = new Uint8Array([0xFA, 0xDC, 0xE2, 0x45]);
  const MAGIC_UINT32 = 0xFADCE245; // big-endian
  const NONCE_LEN   = 12;
  const HEADER_LEN  = MAGIC_BYTES.length + NONCE_LEN; // 16 bytes total

  /**
   * Check the first 4 bytes of |buf| for the FadCam magic.
   * @param {ArrayBuffer} buf
   * @returns {boolean}
   */
  function hasMagic(buf) {
    if (buf.byteLength < HEADER_LEN) return false;
    return new DataView(buf).getUint32(0, false /* big-endian */) === MAGIC_UINT32;
  }

  /**
   * Build the custom loader class once HLS.js is available.
   * We extend Hls.DefaultConfig.loader so all the XHR plumbing is inherited.
   */
  function buildLoader() {
    if (typeof Hls === 'undefined') {
      console.error('[FadCamFragLoader] HLS.js is not loaded — cannot create loader');
      return null;
    }

    const DefaultLoader = Hls.DefaultConfig.loader;

    class FadCamFragLoader extends DefaultLoader {
      /**
       * @param {Object} config  HLS.js loader config (passed by HLS.js internals).
       */
      constructor(config) {
        super(config);
      }

      /**
       * Override load() only for non-init fragment requests.
       * @param {Object}   context   HLS.js fragment context.
       * @param {Object}   config    Loader config.
       * @param {Object}   callbacks HLS.js callbacks: { onSuccess, onError, onTimeout, onAbort }.
       */
      load(context, config, callbacks) {
        // Pass init segments through unmodified (never encrypted).
        // HLS.js sets context.type to a PlaylistLevelType ('main', 'audio', etc.) — never 'fragment'.
        // Use frag.sn type: numeric = media segment, string 'initSegment' = init segment.
        const isMediaSegment = context.frag &&
          typeof context.frag.sn === 'number' &&
          !context.url.includes('init.mp4');

        if (!isMediaSegment) {
          super.load(context, config, callbacks);
          return;
        }

        // Intercept: patch the onSuccess callback to decrypt before forwarding
        const originalOnSuccess = callbacks.onSuccess;

        const patchedCallbacks = Object.assign({}, callbacks, {
          onSuccess: async (response, stats, ctx, networkDetails) => {
            try {
              const decrypted = await decryptSegment(response.data, ctx);
              // Replace the payload with the decrypted buffer
              const patchedResponse = Object.assign({}, response, { data: decrypted });
              originalOnSuccess(patchedResponse, stats, ctx, networkDetails);
            } catch (err) {
              console.error('[FadCamFragLoader] Decryption error:', err.message);
              // Dispatch event so UI can prompt for re-unlock
              window.dispatchEvent(new CustomEvent('e2e-decryption-failed', { detail: { error: err.message } }));
              callbacks.onError(
                { code: 0, text: err.message },
                ctx,
                networkDetails,
                stats
              );
            }
          },
        });

        super.load(context, config, patchedCallbacks);
      }
    }

    return FadCamFragLoader;
  }

  /**
   * Decrypt a single E2E-encrypted segment payload.
   *
   * @param {ArrayBuffer} buf   Raw bytes fetched from the relay server.
   * @param {Object}      ctx   HLS.js fragment context (for deviceId lookup).
   * @returns {Promise<ArrayBuffer>} Decrypted segment bytes.
   */
  async function decryptSegment(buf, ctx) {
    // Strict magic check — no fallback
    if (!hasMagic(buf)) {
      throw new Error('E2E: invalid segment header (missing 0xFADCE245 magic)');
    }

    if (buf.byteLength < HEADER_LEN + 16 /* min GCM auth tag */) {
      throw new Error('E2E: segment too short to be valid (' + buf.byteLength + ' bytes)');
    }

    const nonce      = buf.slice(MAGIC_BYTES.length, HEADER_LEN);
    const ciphertext = buf.slice(HEADER_LEN); // includes 16-byte GCM tag at end

    // Retrieve device key from E2EKeyManager
    const deviceId = getStreamDeviceId();
    if (!deviceId) {
      throw new Error('E2E: stream device ID not available');
    }
    if (typeof E2EKeyManager === 'undefined') {
      throw new Error('E2E: E2EKeyManager not loaded');
    }
    const deviceKey = await E2EKeyManager.getDeviceKey(deviceId);

    // AES-256-GCM decrypt (GCM authenticates automatically — throws on tag mismatch)
    return crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: nonce },
      deviceKey,
      ciphertext
    );
  }

  /**
   * Get the current stream device ID from FadCamRemote (cloud mode).
   * @returns {string|null}
   */
  function getStreamDeviceId() {
    if (typeof FadCamRemote !== 'undefined' && typeof FadCamRemote.getStreamDeviceId === 'function') {
      return FadCamRemote.getStreamDeviceId();
    }
    return null;
  }

  // Build and export — HLS.js must already be on the page
  const loader = buildLoader();
  if (loader) {
    window.FadCamFragLoader = loader;
    console.log('[FadCamFragLoader] Loaded and ready');
  }

})();

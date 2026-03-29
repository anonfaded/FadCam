/**
 * E2EKeyManager — End-to-end stream encryption key management for the FadCam dashboard.
 *
 * Key derivation chain (must be identical to Android StreamKeyManager):
 *   password + user_uuid
 *       ↓  PBKDF2-SHA256, 600 000 iterations, 512-bit output
 *   master_key  (64 bytes)
 *       ├──  HMAC-SHA256(master_key, "fadcam-e2e-v1")  →  verify_tag  (hex-encoded, stored in Supabase)
 *       └──  HKDF-SHA256(master_key, "fadcam-stream-v1" + deviceId)  →  device_key  (32 bytes, AES-256)
 *
 * Persistence: master_key CryptoKey stored in IndexedDB ("fadcam-e2e" / "keys" / "master").
 * The CryptoKey is non-extractable for device_key derivation, but we store the raw bytes
 * of the master_key so we can run HMAC on it to compute the verify_tag on demand.
 *
 * All crypto uses window.crypto.subtle — no external libraries.
 */
(function () {
  'use strict';

  const DB_NAME   = 'fadcam-e2e';
  const STORE     = 'keys';
  const MASTER_ID = 'master';

  const E2E_VERIFY_INFO = 'fadcam-e2e-v1';
  const STREAM_KEY_INFO_PREFIX = 'fadcam-stream-v1';

  const enc = (s) => new TextEncoder().encode(s);

  // ── IndexedDB helpers ────────────────────────────────────────────────────────

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = (e) => {
        e.target.result.createObjectStore(STORE);
      };
      req.onsuccess = (e) => resolve(e.target.result);
      req.onerror   = ()  => reject(req.error);
    });
  }

  async function dbGet(key) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx  = db.transaction(STORE, 'readonly');
      const req = tx.objectStore(STORE).get(key);
      req.onsuccess = () => resolve(req.result ?? null);
      req.onerror   = () => reject(req.error);
    });
  }

  async function dbPut(key, value) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx  = db.transaction(STORE, 'readwrite');
      const req = tx.objectStore(STORE).put(value, key);
      req.onsuccess = () => resolve();
      req.onerror   = () => reject(req.error);
    });
  }

  async function dbDelete(key) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
      const tx  = db.transaction(STORE, 'readwrite');
      const req = tx.objectStore(STORE).delete(key);
      req.onsuccess = () => resolve();
      req.onerror   = () => reject(req.error);
    });
  }

  // ── Crypto helpers ───────────────────────────────────────────────────────────

  /**
   * PBKDF2-SHA256(password, salt=userUuid, 600 000 iter) → 64-byte ArrayBuffer.
   */
  async function pbkdf2(password, userUuid) {
    const baseKey = await crypto.subtle.importKey(
      'raw', enc(password),
      { name: 'PBKDF2' },
      false,
      ['deriveBits']
    );
    return crypto.subtle.deriveBits(
      { name: 'PBKDF2', hash: 'SHA-256', salt: enc(userUuid), iterations: 600_000 },
      baseKey,
      512 // bits = 64 bytes
    );
  }

  /**
   * HMAC-SHA256(key, message) → hex string.
   */
  async function hmacHex(keyBytes, message) {
    const hmacKey = await crypto.subtle.importKey(
      'raw', keyBytes,
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign']
    );
    const sig = await crypto.subtle.sign('HMAC', hmacKey, enc(message));
    return Array.from(new Uint8Array(sig))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }

  /**
   * HKDF-SHA256 single-round expand (T1 = HMAC-SHA256(prk, info || 0x01)).
   * Produces exactly 32 bytes suitable for AES-256-GCM.
   */
  async function hkdfExpand(prkBytes, info) {
    const mac = await crypto.subtle.importKey(
      'raw', prkBytes,
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign']
    );
    const infoBytes   = enc(info);
    const combined    = new Uint8Array(infoBytes.length + 1);
    combined.set(infoBytes);
    combined[infoBytes.length] = 0x01; // counter = 1
    const t1 = await crypto.subtle.sign('HMAC', mac, combined);
    return new Uint8Array(t1).slice(0, 32); // first 32 bytes = AES-256 key
  }

  // ── Public API ───────────────────────────────────────────────────────────────

  /**
   * Derive master key from password + userUuid, compute verify_tag,
   * optionally PUT it to Supabase, then persist master key bytes in IndexedDB.
   *
   * @param {string} password            User's FadSec ID password.
   * @param {string} userUuid            User's UUID (PBKDF2 salt).
   * @param {string} supabaseAccessToken Supabase JWT for the authenticated user.
   * @param {string} supabaseUrl         Supabase project URL.
   * @param {string} supabaseAnonKey     Supabase anon key.
   * @returns {Promise<string>}          The computed verify_tag hex string.
   */
  async function initFromPassword(password, userUuid, supabaseAccessToken, supabaseUrl, supabaseAnonKey) {
    console.log('[E2EKeyManager] Deriving master key…');
    const masterBuf  = await pbkdf2(password, userUuid);
    const masterBytes = new Uint8Array(masterBuf);

    // Compute verify_tag and upsert to Supabase
    const verifyTag = await hmacHex(masterBytes, E2E_VERIFY_INFO);
    console.log('[E2EKeyManager] verify_tag computed:', verifyTag.substring(0, 8) + '…');

    try {
      const resp = await fetch(`${supabaseUrl}/rest/v1/users?id=eq.${encodeURIComponent(userUuid)}`, {
        method: 'PATCH',
        headers: {
          'Content-Type':      'application/json',
          'Authorization':     `Bearer ${supabaseAccessToken}`,
          'apikey':            supabaseAnonKey,
          'Prefer':            'return=minimal',
        },
        body: JSON.stringify({ e2e_verify_tag: verifyTag }),
      });
      if (!resp.ok) {
        console.warn('[E2EKeyManager] Supabase upsert failed (HTTP', resp.status, ') — continuing without server sync');
      } else {
        console.log('[E2EKeyManager] verify_tag stored in Supabase');
      }
    } catch (err) {
      console.warn('[E2EKeyManager] Supabase upsert network error:', err.message);
    }

    // Persist master key bytes in IndexedDB
    await dbPut(MASTER_ID, masterBytes);
    console.log('[E2EKeyManager] Master key stored in IndexedDB');
    return verifyTag;
  }

  /**
   * Returns true if the master key is stored in IndexedDB.
   */
  async function isInitialized() {
    try {
      const entry = await dbGet(MASTER_ID);
      return entry !== null;
    } catch {
      return false;
    }
  }

  /**
   * Derive master key from password + userUuid, compare its HMAC against
   * the provided verifyTag, and if it matches persist the master key.
   *
   * @param {string} password   Candidate password.
   * @param {string} userUuid   User UUID.
   * @param {string} verifyTag  Expected 64-char hex verify_tag from Supabase.
   * @returns {Promise<boolean>} true = password correct and key stored.
   */
  async function unlock(password, userUuid, verifyTag) {
    const masterBuf   = await pbkdf2(password, userUuid);
    const masterBytes = new Uint8Array(masterBuf);
    const computed    = await hmacHex(masterBytes, E2E_VERIFY_INFO);

    if (computed !== verifyTag) {
      console.warn('[E2EKeyManager] Unlock failed — incorrect password');
      return false;
    }

    await dbPut(MASTER_ID, masterBytes);
    console.log('[E2EKeyManager] Unlocked and master key persisted');
    return true;
  }

  /**
   * Derive master key from password + userUuid and persist it WITHOUT server-side
   * verify_tag validation. Use this only when the verify_tag is not yet available
   * (e.g. race condition on first device link while PBKDF2 is still running on device).
   * If the password is wrong the stream will fail to decrypt and re-prompt the user.
   *
   * @param {string} password  Candidate password.
   * @param {string} userUuid  User UUID.
   * @returns {Promise<void>}
   */
  async function unlockNoVerify(password, userUuid) {
    const masterBuf   = await pbkdf2(password, userUuid);
    const masterBytes = new Uint8Array(masterBuf);
    await dbPut(MASTER_ID, masterBytes);
    console.log('[E2EKeyManager] unlockNoVerify: master key derived and persisted (no server validation)');
  }

  /**
   * Derive the 32-byte AES-256-GCM device key for the given deviceId.
   * Returns a non-extractable CryptoKey ready for crypto.subtle.decrypt.
   *
   * @param {string} deviceId
   * @returns {Promise<CryptoKey>}
   */
  async function getDeviceKey(deviceId) {
    const masterBytes = await dbGet(MASTER_ID);
    if (!masterBytes) {
      throw new Error('[E2EKeyManager] Master key not initialised — call unlock() first');
    }
    const deviceKeyBytes = await hkdfExpand(masterBytes, STREAM_KEY_INFO_PREFIX + deviceId);
    return crypto.subtle.importKey(
      'raw', deviceKeyBytes,
      { name: 'AES-GCM', length: 256 },
      false, // non-extractable
      ['decrypt']
    );
  }

  /**
   * Delete the master key from IndexedDB (called on logout).
   */
  async function clear() {
    try {
      await dbDelete(MASTER_ID);
      console.log('[E2EKeyManager] Master key cleared from IndexedDB');
    } catch (err) {
      console.warn('[E2EKeyManager] Error clearing master key:', err);
    }
  }

  // ── Export ───────────────────────────────────────────────────────────────────

  window.E2EKeyManager = { initFromPassword, isInitialized, unlock, unlockNoVerify, getDeviceKey, clear };

})();

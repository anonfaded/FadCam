/**
 * FadCam Remote Cloud Integration
 * 
 * Adds cloud features to the dashboard when accessed via web.
 * Shows on: fadseclab.com, localhost
 * Hidden on: IP addresses (phone/LAN access like 192.168.x.x)
 * 
 * Per-device streaming:
 * - /stream/{device_id}/ → connects to specific device's stream
 * - /dashboard/ → demo mode (no real streaming)
 * 
 * Cross-domain authentication:
 * - User clicks "Open Stream" at id.fadseclab.com
 * - Lab generates handoff token and redirects here with ?token=xxx
 * - We exchange token for session data via Edge Function
 * - Session stored in localStorage for subsequent API calls
 * 
 * Real device management happens at id.fadseclab.com/lab (Phase 6).
 */
(function() {
  'use strict';
  
  // Configuration (renamed to avoid conflict with global CONFIG)
  const CLOUD_CONFIG = {
    LAB_URL: 'https://id.fadseclab.com/lab',
    SUPABASE_URL: 'https://vfhehknmxxedvesdvpew.supabase.co',
    SUPABASE_ANON_KEY: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZmaGVoa25teHhlZHZlc2R2cGV3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3Mzc3NDI2ODIsImV4cCI6MjA1MzMxODY4Mn0.1F8NF0IwBE-GYmR8Yrq4FKfFGKBRIUhWs0_fzFqF0gc',
    // Domains where cloud features should show
    WEB_DOMAINS: [
      'fadseclab.com',
      'localhost'
    ],
    // Local storage keys
    STORAGE_KEYS: {
      SESSION: 'fadcam_session',
      USER: 'fadcam_user',
      STREAM_TOKEN: 'fadcam_stream_token', // JWT for relay API calls
      E2E_VERIFY_TAG: 'fadcam_e2e_verify_tag' // HMAC verify tag from Supabase
    }
  };
  
  // Stream context (set when accessing /stream/{device_id}/)
  let streamContext = null;
  
  // Check if running on web (not phone/LAN access)
  function isWebAccess() {
    const hostname = window.location.hostname;
    return CLOUD_CONFIG.WEB_DOMAINS.some(domain => hostname.includes(domain));
  }
  
  // Extract device_id from URL if in streaming mode
  // Supports both:
  // - /stream/{device_id}/ (direct path, works in dev mode)
  // - /stream/?device={device_id} (GitHub Pages SPA fallback)
  function getStreamDeviceId() {
    // First check query parameter (GitHub Pages SPA fallback)
    const params = new URLSearchParams(window.location.search);
    const deviceFromQuery = params.get('device');
    if (deviceFromQuery) {
      return deviceFromQuery;
    }
    
    // Then check path (direct path, works in dev mode)
    const path = window.location.pathname;
    const match = path.match(/^\/stream\/([a-zA-Z0-9_-]+)/);
    return match ? match[1] : null;
  }
  
  // Get handoff token from URL (passed by Lab after generating)
  function getHandoffToken() {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    console.log('[FadCamRemote] getHandoffToken:', { 
      search: window.location.search, 
      hasToken: !!token,
      tokenLength: token?.length 
    });
    return token;
  }
  
  // Get stored session from localStorage
  function getStoredSession() {
    try {
      const sessionStr = localStorage.getItem(CLOUD_CONFIG.STORAGE_KEYS.SESSION);
      if (!sessionStr) return null;
      const session = JSON.parse(sessionStr);
      // Check if session is still valid (authenticated within last 24 hours)
      const authTime = new Date(session.authenticated_at);
      const now = new Date();
      const hoursDiff = (now - authTime) / (1000 * 60 * 60);
      if (hoursDiff > 24) {
        // Session expired, clear it
        localStorage.removeItem(CLOUD_CONFIG.STORAGE_KEYS.SESSION);
        localStorage.removeItem(CLOUD_CONFIG.STORAGE_KEYS.USER);
        return null;
      }
      return session;
    } catch (e) {
      return null;
    }
  }
  
  // Store session in localStorage
  function storeSession(sessionData, userData, streamToken, e2eVerifyTag) {
    localStorage.setItem(CLOUD_CONFIG.STORAGE_KEYS.SESSION, JSON.stringify(sessionData));
    localStorage.setItem(CLOUD_CONFIG.STORAGE_KEYS.USER, JSON.stringify(userData));
    if (streamToken) {
      localStorage.setItem(CLOUD_CONFIG.STORAGE_KEYS.STREAM_TOKEN, streamToken);
    }
    // Only update verify_tag when the server provides one.
    // Never wipe an existing tag when null is returned — that typically means the
    // Android device's PBKDF2 thread hasn't finished writing it to Supabase yet
    // (race condition on first link). Keeping the old tag avoids breaking sessions
    // that were already unlocked.
    if (e2eVerifyTag) {
      localStorage.setItem(CLOUD_CONFIG.STORAGE_KEYS.E2E_VERIFY_TAG, e2eVerifyTag);
      console.log('[FadCamRemote] E2E verify_tag stored from token exchange');
    } else {
      console.log('[FadCamRemote] Token exchange returned null e2e_verify_tag — keeping existing tag if any');
    }
  }

  // Get stored E2E verify tag
  function getE2EVerifyTag() {
    return localStorage.getItem(CLOUD_CONFIG.STORAGE_KEYS.E2E_VERIFY_TAG) || null;
  }

  // Get user ID from stored session
  function getSessionUserId() {
    try {
      const sessionStr = localStorage.getItem(CLOUD_CONFIG.STORAGE_KEYS.SESSION);
      if (!sessionStr) return null;
      const session = JSON.parse(sessionStr);
      return session.user_id || null;
    } catch { return null; }
  }
  
  // Get stored stream access token
  function getStreamToken() {
    return localStorage.getItem(CLOUD_CONFIG.STORAGE_KEYS.STREAM_TOKEN);
  }
  
  // Exchange handoff token for session (called when arriving from Lab)
  async function exchangeHandoffToken(token, deviceId) {
    console.log('[FadCamRemote] Exchanging handoff token...');
    
    try {
      const response = await fetch(`${CLOUD_CONFIG.SUPABASE_URL}/functions/v1/exchange-handoff-token`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ token, device_id: deviceId })
      });
      
      const result = await response.json();
      
      if (!response.ok) {
        throw new Error(result.error || 'Token exchange failed');
      }
      
      // Store the session, stream access token, and E2E verify tag for future use
      storeSession(result.session_hint, result.user, result.stream_access_token, result.e2e_verify_tag || null);
      
      // Clean up the URL (remove token from URL for security/aesthetics)
      const cleanUrl = new URL(window.location.href);
      cleanUrl.searchParams.delete('token');
      window.history.replaceState({}, document.title, cleanUrl.toString());
      
      console.log('[FadCamRemote] Token exchanged successfully for user:', result.user.email);
      return result;
      
    } catch (error) {
      console.error('[FadCamRemote] Token exchange failed:', error);
      throw error;
    }
  }
  
  // Check if we're in demo mode (/dashboard/) or streaming mode (/stream/{id}/)
  function isStreamingMode() {
    return getStreamDeviceId() !== null;
  }

  // ── E2E Unlock Modal ────────────────────────────────────────────────────────

  /**
   * Show the E2E unlock modal (injected dynamically into <body>).
   * Called when: E2E key is missing from IndexedDB and stream is encrypted.
   * Supports two modes:
   *   - Normal (verifyTag present): validates password against server tag before storing key.
   *   - First-time / race-condition (verifyTag null): derives and stores key without validation;
   *     decryption failure will re-prompt automatically.
   */
  function showE2EUnlockModal() {
    // Remove existing modal if any
    const existing = document.getElementById('e2e-unlock-overlay');
    if (existing) existing.remove();

    const userId  = (streamContext && streamContext.userId) || getSessionUserId();
    const verifyTag = getE2EVerifyTag();
    // true = first-time / race condition (PBKDF2 may not have written tag to Supabase yet)
    const noVerifyTag = !verifyTag;

    const overlay = document.createElement('div');
    overlay.id = 'e2e-unlock-overlay';
    overlay.style.cssText = [
      'position:fixed', 'inset:0', 'background:rgba(0,0,0,0.92)',
      'display:flex', 'align-items:center', 'justify-content:center',
      'z-index:10000', 'font-family:system-ui,sans-serif',
    ].join(';');

    const subtitle = noVerifyTag
      ? 'This stream appears to be end-to-end encrypted. Enter your FadSec ID password to decrypt & unlock playback.'
      : 'This stream is end-to-end encrypted. Enter your FadSec ID password to decrypt & unlock playback.';

    overlay.innerHTML = `
      <div style="background:#1a1a2e;border:1px solid #333;border-radius:12px;padding:32px 28px;
                  max-width:400px;width:90%;color:#fff;text-align:center;">
        <div style="font-size:40px;margin-bottom:12px;">&#x1F512;</div>
        <h2 style="margin:0 0 8px;font-size:18px;font-weight:700;">
          E2E Stream Encryption
        </h2>
        <p style="margin:0 0 24px;font-size:13px;color:#aaa;">
          ${subtitle}
        </p>
        <input
          id="e2e-unlock-input"
          type="password"
          placeholder="FadSec ID password"
          autocomplete="current-password"
          style="width:100%;box-sizing:border-box;padding:10px 14px;
                 background:#0d0d1a;border:1px solid #444;border-radius:8px;
                 color:#fff;font-size:14px;margin-bottom:12px;
                 outline:none;transition:border-color .2s;"
        />
        <div id="e2e-unlock-error"
             style="color:#ff5555;font-size:12px;min-height:20px;margin-bottom:12px;"></div>
        <button
          id="e2e-unlock-btn"
          onclick="window.__e2eUnlockSubmit()"
          style="width:100%;padding:11px;background:#3a86ff;border:none;
                 border-radius:8px;color:#fff;font-size:14px;font-weight:600;
                 cursor:pointer;transition:background .2s;"
        >
          Unlock
        </button>
      </div>`;

    document.body.appendChild(overlay);

    // Focus the input
    const input = document.getElementById('e2e-unlock-input');
    if (input) setTimeout(() => input.focus(), 50);

    // Enter key submits
    if (input) {
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') window.__e2eUnlockSubmit(); });
    }

    // Attach submit handler on window to keep it accessible from inline onclick
    window.__e2eUnlockSubmit = async function () {
      const btn   = document.getElementById('e2e-unlock-btn');
      const errEl = document.getElementById('e2e-unlock-error');
      const pw    = (document.getElementById('e2e-unlock-input') || {}).value || '';

      if (!pw) {
        if (errEl) errEl.textContent = 'Password is required.';
        return;
      }

      if (btn) { btn.disabled = true; btn.textContent = 'Unlocking…'; }
      if (errEl) errEl.textContent = '';

      try {
        if (!userId) throw new Error('User ID unavailable — please re-authenticate.');

        if (noVerifyTag) {
          // Race condition: verify_tag not yet in Supabase (PBKDF2 still running on device).
          // Derive and store the key without server validation. If the password is wrong the
          // stream decryption will fail and the e2e-decryption-failed event will re-prompt.
          console.log('[FadCamRemote] No verify_tag available — unlocking without server validation (first-time / race condition)');
          await E2EKeyManager.unlockNoVerify(pw, userId);
        } else {
          const ok = await E2EKeyManager.unlock(pw, userId, verifyTag);
          if (!ok) {
            if (errEl) errEl.textContent = 'Incorrect password. Please try again.';
            if (btn) { btn.disabled = false; btn.textContent = 'Unlock'; }
            return;
          }
        }

        // Success — dismiss modal and signal stream to reload
        const el = document.getElementById('e2e-unlock-overlay');
        if (el) {
          el.style.opacity = '0';
          el.style.transition = 'opacity .3s';
          setTimeout(() => el.remove(), 300);
        }
        delete window.__e2eUnlockSubmit;
        console.log('[FadCamRemote] E2E unlocked successfully');
        // Notify the stream player to reload so it can decrypt with the new key
        window.dispatchEvent(new CustomEvent('e2e-stream-ready'));
      } catch (err) {
        console.error('[FadCamRemote] E2E unlock error:', err);
        if (errEl) errEl.textContent = err.message || 'Unlock failed. Please try again.';
        if (btn) { btn.disabled = false; btn.textContent = 'Unlock'; }
      }
    };
  }

  /**
   * Check if the E2E unlock modal should be shown and show it if needed.
   * Shows when: verify_tag exists (E2E is configured) AND key is not in IndexedDB.
   */
  async function checkAndShowE2EUnlock() {
    const verifyTag = getE2EVerifyTag();
    if (!verifyTag) {
      console.log('[FadCamRemote] No E2E verify_tag — stream is not encrypted');
      return;
    }

    if (typeof E2EKeyManager === 'undefined') {
      console.warn('[FadCamRemote] E2EKeyManager not loaded — cannot check E2E state');
      return;
    }

    const initialized = await E2EKeyManager.isInitialized();
    if (!initialized) {
      console.log('[FadCamRemote] E2E key not in IndexedDB — showing unlock modal');
      showE2EUnlockModal();
    } else {
      console.log('[FadCamRemote] E2E key already in IndexedDB — stream ready');
    }
  }

  // Listen for decryption failures → show unlock modal (debounced, non-destructive).
  // CRITICAL: Do NOT clear the key on every failure. HLS fires this event for every
  // segment retry, and clearing the key mid-typing makes it impossible for the user
  // to enter their password. Only clear the key when the user entered a wrong password
  // (handled inside showE2EUnlockModal's submit handler).
  let _e2eModalShowing = false;
  window.addEventListener('e2e-decryption-failed', async (ev) => {
    console.warn('[FadCamRemote] e2e-decryption-failed event received:', ev.detail);
    // Skip if the unlock modal is already visible (avoid overlapping prompts)
    if (_e2eModalShowing || document.getElementById('e2e-unlock-overlay')) {
      return;
    }
    _e2eModalShowing = true;
    showE2EUnlockModal();
    // Reset flag when modal is dismissed (overlay removed from DOM)
    const observer = new MutationObserver(() => {
      if (!document.getElementById('e2e-unlock-overlay')) {
        _e2eModalShowing = false;
        observer.disconnect();
      }
    });
    observer.observe(document.body, { childList: true });
  });
  
  // Initialize stream context for device
  async function initStreamContext(deviceId) {
    console.log('[FadCamRemote] Initializing stream for device:', deviceId);
    
    // Show connecting overlay
    showStreamOverlay('Connecting...', 'Authenticating with FadSec Cloud');
    
    try {
      // Check for handoff token first (coming from Lab)
      const handoffToken = getHandoffToken();
      console.log('[FadCamRemote] Token check result:', { 
        handoffToken: handoffToken ? handoffToken.substring(0, 8) + '...' : null,
        willExchange: !!handoffToken 
      });
      let session = null;
      
      if (handoffToken) {
        console.log('[FadCamRemote] HAS handoff token, will exchange...');
        // Exchange handoff token for session
        try {
          const result = await exchangeHandoffToken(handoffToken, deviceId);
          session = result.session_hint;
          streamContext = {
            deviceId: deviceId,
            deviceName: result.device?.name || deviceId,
            userId: result.user.id,
            userEmail: result.user.email,
            streamToken: result.stream_access_token // JWT for relay API calls
          };
        } catch (e) {
          showStreamOverlay('Authentication Failed', e.message || 'Invalid or expired link. Please try again from Lab.');
          setTimeout(() => {
            window.location.href = CLOUD_CONFIG.LAB_URL;
          }, 3000);
          return;
        }
      } else {
        console.log('[FadCamRemote] NO handoff token, checking stored session...');
        // No handoff token - check for stored session
        session = getStoredSession();
        const storedStreamToken = getStreamToken();
        console.log('[FadCamRemote] Stored session:', session ? 'exists' : 'none', 'Stream token:', storedStreamToken ? 'exists' : 'none');
        
        if (!session || !storedStreamToken) {
          console.log('[FadCamRemote] No session or token, will redirect to Lab in 1.5s');
          // No session - redirect to Lab to login
          showStreamOverlay('Not Logged In', 'Redirecting to login...');
          setTimeout(() => {
            console.log('[FadCamRemote] Redirecting NOW to:', CLOUD_CONFIG.LAB_URL);
            window.location.href = CLOUD_CONFIG.LAB_URL;
          }, 1500);
          return;
        }
        
        // CRITICAL SECURITY: Validate stored session with server before granting access
        // This ensures users with revoked access cannot bypass tier restrictions
        // If tier/beta status changed since last login, this will catch it
        console.log('[FadCamRemote] 🔐 Validating stored session with server...');
        try {
          const validationResponse = await fetch(`${CLOUD_CONFIG.SUPABASE_URL}/functions/v1/verify-stream-token`, {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${storedStreamToken}`,
              'X-Device-Id': deviceId,
              'X-User-Id': session.user_id,
              'Content-Type': 'application/json'
            },
            body: JSON.stringify({ device_id: deviceId, user_id: session.user_id })
          });
          
          if (!validationResponse.ok) {
            console.error(`[FadCamRemote] ❌ Session validation failed: HTTP ${validationResponse.status}`);
            console.log('[FadCamRemote] User loses access: tier changed, beta revoked, or quota exceeded');
            // Clear stored session and E2E key — user no longer has access
            localStorage.removeItem(CLOUD_CONFIG.STORAGE_KEYS.SESSION);
            localStorage.removeItem(CLOUD_CONFIG.STORAGE_KEYS.USER);
            localStorage.removeItem(CLOUD_CONFIG.STORAGE_KEYS.STREAM_TOKEN);
            localStorage.removeItem(CLOUD_CONFIG.STORAGE_KEYS.E2E_VERIFY_TAG);
            if (typeof E2EKeyManager !== 'undefined') E2EKeyManager.clear();
            
            showStreamOverlay('Access Revoked', 'Your streaming access has been revoked or expired. Please log in again from Lab.');
            setTimeout(() => {
              window.location.href = CLOUD_CONFIG.LAB_URL;
            }, 3000);
            return;
          }
          
          console.log('[FadCamRemote] ✅ Session validated - user has current access');
        } catch (validationError) {
          console.error('[FadCamRemote] Session validation error:', validationError);
          // Network error or server issue - fail safely by requiring re-auth
          console.log('[FadCamRemote] 🛡️ Failing safe - network error, redirecting to login');
          showStreamOverlay('Connection Failed', 'Unable to verify access. Please try again from Lab.');
          setTimeout(() => {
            window.location.href = CLOUD_CONFIG.LAB_URL;
          }, 3000);
          return;
        }
        
        // We have a valid, server-verified stored session, use it
        streamContext = {
          deviceId: deviceId,
          deviceName: session.device_id === deviceId ? 'Your Device' : deviceId,
          userId: session.user_id,
          streamToken: storedStreamToken // JWT for relay API calls
        };
      }
      
      // Hide overlay and start streaming
      hideStreamOverlay();
      // Logo already links to Lab (set in HTML), no need to update
      
      // Initialize CloudApiService for cloud mode
      if (typeof initCloudApiService === 'function') {
        initCloudApiService(streamContext);
        console.log('[FadCamRemote] CloudApiService initialized');
      }
      
      console.log('[FadCamRemote] Stream context ready:', streamContext);

      // Show E2E unlock modal if verify_tag is present but key is not in IndexedDB
      await checkAndShowE2EUnlock();
      
      // Emit event for DashboardViewModel to pick up cloud mode
      if (typeof eventBus !== 'undefined') {
        eventBus.emit('cloud-mode-ready', streamContext);
      }
      
    } catch (error) {
      console.error('[FadCamRemote] Stream init failed:', error);
      showStreamOverlay('Connection Failed', 'Unable to connect to device. Please try again.');
    }
  }
  
  // Check if we're in cloud mode (web access + authenticated stream context)
  function isCloudMode() {
    return isWebAccess() && streamContext !== null;
  }
  
  // Show overlay on stream page
  function showStreamOverlay(title, message) {
    let overlay = document.getElementById('stream-overlay');
    if (!overlay) {
      overlay = document.createElement('div');
      overlay.id = 'stream-overlay';
      overlay.style.cssText = `
        position: fixed;
        inset: 0;
        background: rgba(0,0,0,0.95);
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        z-index: 9999;
        color: white;
        text-align: center;
        padding: 20px;
      `;
      document.body.appendChild(overlay);
    }
    
    overlay.innerHTML = `
      <div style="max-width: 400px;">
        <div style="font-size: 24px; font-weight: bold; margin-bottom: 12px;">${title}</div>
        <div style="font-size: 14px; color: #888;">${message}</div>
        <div style="margin-top: 20px;">
          <div style="width: 40px; height: 40px; border: 3px solid #333; border-top-color: #ff4444; border-radius: 50%; animation: spin 1s linear infinite; margin: 0 auto;"></div>
        </div>
      </div>
      <style>
        @keyframes spin { to { transform: rotate(360deg); } }
      </style>
    `;
  }
  
  // Hide overlay
  function hideStreamOverlay() {
    const overlay = document.getElementById('stream-overlay');
    if (overlay) {
      overlay.style.opacity = '0';
      overlay.style.transition = 'opacity 0.3s';
      setTimeout(() => overlay.remove(), 300);
    }
  }
  
  // Show device banner at top
  // NOTE: Device banner removed - logo in header always links to Lab (set in HTML)
  
  // Add FadCam Remote menu item to profile dropdown
  function addCloudMenuItem() {
    const dropdown = document.getElementById('profileDropdown');
    if (!dropdown) {
      console.error('[FadCamRemote] Profile dropdown not found');
      return;
    }
    
    // Create the menu item - always shows "My Account" link to Lab
    const menuItem = document.createElement('div');
    menuItem.className = 'profile-item fadcam-remote-item';
    menuItem.style.cssText = 'border-top: 1px solid rgba(255,255,255,0.1); margin-top: 4px; padding-top: 12px;';
    
    menuItem.innerHTML = `
      <i class="fas fa-cloud" style="color: #00d4ff;"></i> 
      <span>FadCam Remote</span>
    `;
    menuItem.onclick = () => window.open(CLOUD_CONFIG.LAB_URL, '_blank');
    
    // Insert before the last item (Logout)
    const logoutItem = dropdown.querySelector('.profile-item:last-child');
    if (logoutItem) {
      dropdown.insertBefore(menuItem, logoutItem);
    } else {
      dropdown.appendChild(menuItem);
    }
    
    console.log('[FadCamRemote] Menu item added');
  }
  
  // Initialize
  function init() {
    if (!isWebAccess()) {
      console.log('[FadCamRemote] Local/LAN access, skipping cloud features');
      return;
    }
    
    console.log('[FadCamRemote] Web access detected, adding cloud features...');
    
    // Check if we're in streaming mode
    const deviceId = getStreamDeviceId();
    if (deviceId) {
      console.log('[FadCamRemote] Streaming mode for device:', deviceId);
      initStreamContext(deviceId);
    } else {
      // Demo mode - just add menu item
      addCloudMenuItem();
    }
  }
  
  // Wait for DOM
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
  
  // Export for testing and integration
  window.FadCamRemote = {
    getStreamDeviceId,
    isStreamingMode,
    isCloudMode,
    isWebAccess,
    streamContext: () => streamContext,
    getStreamToken: () => streamContext?.streamToken || getStreamToken(),
    getE2EVerifyTag,
    getSessionUserId,
    getRelayHlsUrl: () => {
      if (!streamContext?.userId || !streamContext?.deviceId) return null;
      // Return the base URL without token — HlsService.xhrSetup handles auth injection.
      // This prevents token duplication when xhrSetup and the URL both carry the token.
      return `https://live.fadseclab.com:8443/stream/${streamContext.userId}/${streamContext.deviceId}/live.m3u8`;
    },
    getRelayStatusUrl: () => {
      if (!streamContext?.userId || !streamContext?.deviceId) return null;
      return `https://live.fadseclab.com:8443/api/status/${streamContext.userId}/${streamContext.deviceId}`;
    },
    getRelayCommandUrl: () => {
      if (!streamContext?.userId || !streamContext?.deviceId) return null;
      return `https://live.fadseclab.com:8443/api/command/${streamContext.userId}/${streamContext.deviceId}`;
    }
  };
})();

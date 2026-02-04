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
      STREAM_TOKEN: 'fadcam_stream_token' // JWT for relay API calls
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
  function storeSession(sessionData, userData, streamToken) {
    localStorage.setItem(CLOUD_CONFIG.STORAGE_KEYS.SESSION, JSON.stringify(sessionData));
    localStorage.setItem(CLOUD_CONFIG.STORAGE_KEYS.USER, JSON.stringify(userData));
    if (streamToken) {
      localStorage.setItem(CLOUD_CONFIG.STORAGE_KEYS.STREAM_TOKEN, streamToken);
    }
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
      
      // Store the session and stream access token for future use
      storeSession(result.session_hint, result.user, result.stream_access_token);
      
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
        
        // We have a stored session, use it
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
    getRelayHlsUrl: () => {
      if (!streamContext?.userId || !streamContext?.deviceId) return null;
      let url = `https://live.fadseclab.com:8443/stream/${streamContext.userId}/${streamContext.deviceId}/live.m3u8`;
      // Append token as query parameter for auth (avoids CORS issues with headers)
      const token = streamContext?.streamToken || getStreamToken();
      if (token) {
        url += `?token=${encodeURIComponent(token)}`;
      }
      return url;
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

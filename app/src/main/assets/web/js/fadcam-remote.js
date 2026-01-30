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
 * Real device management happens at id.fadseclab.com/lab (Phase 6).
 */
(function() {
  'use strict';
  
  // Configuration
  const CONFIG = {
    LAB_URL: 'https://id.fadseclab.com/lab',
    SUPABASE_URL: 'https://vfhehknmxxedvesdvpew.supabase.co',
    SUPABASE_ANON_KEY: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZmaGVoa25teHhlZHZlc2R2cGV3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3Mzc3NDI2ODIsImV4cCI6MjA1MzMxODY4Mn0.1F8NF0IwBE-GYmR8Yrq4FKfFGKBRIUhWs0_fzFqF0gc',
    // Domains where cloud features should show
    WEB_DOMAINS: [
      'fadseclab.com',
      'localhost'
    ]
  };
  
  // Stream context (set when accessing /stream/{device_id}/)
  let streamContext = null;
  
  // Check if running on web (not phone/LAN access)
  function isWebAccess() {
    const hostname = window.location.hostname;
    return CONFIG.WEB_DOMAINS.some(domain => hostname.includes(domain));
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
      // Get auth token from localStorage (set by id.fadseclab.com)
      const authToken = localStorage.getItem('supabase_access_token');
      
      if (!authToken) {
        // Not logged in - redirect to login
        showStreamOverlay('Not Logged In', 'Redirecting to login...');
        setTimeout(() => {
          const returnUrl = encodeURIComponent(window.location.href);
          window.location.href = `${CONFIG.LAB_URL}?return=${returnUrl}`;
        }, 1500);
        return;
      }
      
      // Verify token and device access
      const response = await fetch(`${CONFIG.SUPABASE_URL}/functions/v1/verify-token`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${authToken}`
        },
        body: JSON.stringify({ device_id: deviceId })
      });
      
      if (!response.ok) {
        const error = await response.json();
        showStreamOverlay('Access Denied', error.message || 'You do not have access to this device');
        return;
      }
      
      const data = await response.json();
      streamContext = {
        deviceId: deviceId,
        deviceName: data.device_name || deviceId,
        streamUrl: data.stream_url, // URL to the HLS stream
        userId: data.user_id
      };
      
      // Hide overlay and start streaming
      hideStreamOverlay();
      showDeviceBanner();
      
      // TODO: Connect to actual stream when relay is set up
      console.log('[FadCamRemote] Stream context ready:', streamContext);
      
    } catch (error) {
      console.error('[FadCamRemote] Stream init failed:', error);
      showStreamOverlay('Connection Failed', 'Unable to connect to device. Please try again.');
    }
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
  function showDeviceBanner() {
    if (!streamContext) return;
    
    const banner = document.createElement('div');
    banner.id = 'stream-device-banner';
    banner.style.cssText = `
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      background: linear-gradient(135deg, #1a1a1a, #2a2a2a);
      border-bottom: 1px solid #333;
      padding: 8px 16px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      z-index: 1000;
      font-size: 12px;
    `;
    banner.innerHTML = `
      <div style="display: flex; align-items: center; gap: 8px;">
        <span style="color: #4CAF50;">●</span>
        <span style="color: white; font-weight: 500;">Streaming: ${streamContext.deviceName}</span>
        <span style="color: #666; font-family: monospace; font-size: 10px;">${streamContext.deviceId.substring(0, 8).toUpperCase()}</span>
      </div>
      <a href="${CONFIG.LAB_URL}" style="color: #888; text-decoration: none; font-size: 11px;">← Back to Lab</a>
    `;
    document.body.prepend(banner);
    
    // Adjust body padding to account for banner
    document.body.style.paddingTop = '44px';
  }
  
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
    menuItem.onclick = () => window.open(CONFIG.LAB_URL, '_blank');
    
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
  
  // Export for testing
  window.FadCamRemote = {
    getStreamDeviceId,
    isStreamingMode,
    streamContext: () => streamContext
  };
})();

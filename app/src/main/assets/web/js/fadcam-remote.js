/**
 * FadCam Remote Cloud Integration
 * 
 * Adds cloud features to the dashboard when accessed via web.
 * Shows on: fadseclab.com, fadcam.faded.dev, localhost
 * Hidden on: IP addresses (phone/LAN access like 192.168.x.x)
 */
(function() {
  'use strict';
  
  // Configuration
  const CONFIG = {
    SUPABASE_PROJECT_REF: 'vfhehknmxxedvesdvpew',
    LOGIN_URL: 'https://id.fadseclab.com/login',
    LAB_URL: 'https://id.fadseclab.com/lab',
    // Domains where cloud features should show
    WEB_DOMAINS: [
      'fadcam.fadseclab.com',
      'localhost'
    ]
  };
  
  // Check if running on web (not phone/LAN access)
  function isWebAccess() {
    const hostname = window.location.hostname;
    return CONFIG.WEB_DOMAINS.some(domain => hostname.includes(domain));
  }
  
  // Check for Supabase session in localStorage
  function getSession() {
    const storageKey = `sb-${CONFIG.SUPABASE_PROJECT_REF}-auth-token`;
    try {
      const sessionData = localStorage.getItem(storageKey);
      if (!sessionData) return null;
      
      const session = JSON.parse(sessionData);
      if (!session.access_token) return null;
      
      // Check expiry
      if (session.expires_at) {
        const expiresAt = session.expires_at * 1000;
        if (Date.now() > expiresAt) return null;
      }
      
      return session;
    } catch (e) {
      console.error('[FadCamRemote] Error parsing session:', e);
      return null;
    }
  }
  
  // Add FadCam Remote menu item to profile dropdown
  function addCloudMenuItem() {
    const dropdown = document.getElementById('profileDropdown');
    if (!dropdown) {
      console.error('[FadCamRemote] Profile dropdown not found');
      return;
    }
    
    const session = getSession();
    const isLoggedIn = !!session;
    
    // Create the menu item
    const menuItem = document.createElement('div');
    menuItem.className = 'profile-item fadcam-remote-item';
    menuItem.style.cssText = 'border-top: 1px solid rgba(255,255,255,0.1); margin-top: 4px; padding-top: 12px;';
    
    if (isLoggedIn) {
      menuItem.innerHTML = `
        <i class="fas fa-cloud" style="color: #00d4ff;"></i> 
        <span>My Account</span>
      `;
      menuItem.onclick = () => window.open(CONFIG.LAB_URL, '_blank');
    } else {
      menuItem.innerHTML = `
        <i class="fas fa-cloud" style="color: #ff6b6b;"></i> 
        <span>Get FadCam Remote</span>
      `;
      menuItem.onclick = () => window.open(CONFIG.LOGIN_URL, '_blank');
    }
    
    // Insert before the last item (Logout)
    const logoutItem = dropdown.querySelector('.profile-item:last-child');
    if (logoutItem) {
      dropdown.insertBefore(menuItem, logoutItem);
    } else {
      dropdown.appendChild(menuItem);
    }
    
    console.log('[FadCamRemote] Menu item added, logged in:', isLoggedIn);
  }
  
  // Initialize
  function init() {
    if (!isWebAccess()) {
      console.log('[FadCamRemote] Local/LAN access, skipping cloud features');
      return;
    }
    
    console.log('[FadCamRemote] Web access detected, adding cloud features...');
    addCloudMenuItem();
  }
  
  // Wait for DOM
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

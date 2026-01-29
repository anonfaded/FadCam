/**
 * FadCam Remote Cloud Integration
 * 
 * Adds cloud features to the dashboard when accessed via web.
 * Shows on: fadseclab.com, localhost
 * Hidden on: IP addresses (phone/LAN access like 192.168.x.x)
 * 
 * Note: This dashboard is demo mode only. Real device management
 * happens at id.fadseclab.com/lab (Phase 6).
 */
(function() {
  'use strict';
  
  // Configuration
  const CONFIG = {
    LAB_URL: 'https://id.fadseclab.com/lab',
    // Domains where cloud features should show
    WEB_DOMAINS: [
      'fadseclab.com',
      'localhost'
    ]
  };
  
  // Check if running on web (not phone/LAN access)
  function isWebAccess() {
    const hostname = window.location.hostname;
    return CONFIG.WEB_DOMAINS.some(domain => hostname.includes(domain));
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
    addCloudMenuItem();
  }
  
  // Wait for DOM
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

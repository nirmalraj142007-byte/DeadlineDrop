/**
 * DeadlineDrop Shell Navigation & Session Handling
 */

function initShell() {
  const signOutBtn = document.getElementById('sign-out-btn');
  if (signOutBtn) {
    signOutBtn.addEventListener('click', () => {
      sessionStorage.removeItem('token');
      sessionStorage.removeItem('role');
      sessionStorage.removeItem('displayName');
      window.location.href = '/';
    });
  }

  const userDisplay = document.getElementById('user-display');
  if (userDisplay) {
    const displayName = sessionStorage.getItem('displayName');
    if (displayName) {
      userDisplay.textContent = displayName;
    }
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initShell);
} else {
  initShell();
}

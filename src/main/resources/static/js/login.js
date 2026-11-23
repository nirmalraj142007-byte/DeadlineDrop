/**
 * DeadlineDrop Login Handler
 */

import { apiFetch, ApiError } from './api.js';
import { banner } from './ui.js';

function initLogin() {
  const form = document.getElementById('login-form');
  const bannerContainer = document.getElementById('banner-container');
  const submitBtn = document.getElementById('submit-btn');

  // Display session expired warning if redirected with ?expired=1
  const params = new URLSearchParams(window.location.search);
  if (params.get('expired') === '1' && bannerContainer) {
    bannerContainer.innerHTML = '';
    bannerContainer.appendChild(banner('warn', 'Your session expired, sign in again'));
  }

  if (form) {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      if (bannerContainer) {
        bannerContainer.innerHTML = '';
      }

      const email = document.getElementById('email').value.trim();
      const password = document.getElementById('password').value;

      if (!email || !password) {
        return;
      }

      if (submitBtn) {
        submitBtn.disabled = true;
      }

      try {
        const data = await apiFetch('/api/auth/login', {
          method: 'POST',
          body: { email, password }
        });

        sessionStorage.setItem('token', data.accessToken);
        sessionStorage.setItem('role', data.role);
        sessionStorage.setItem('displayName', data.displayName);

        if (data.role === 'STUDENT') {
          window.location.href = '/student/assignments.html';
        } else if (data.role === 'ADMIN') {
          window.location.href = '/admin/console.html';
        } else if (data.role === 'TEACHER') {
          window.location.href = '/teacher/courses.html';
        } else {
          window.location.href = '/student/assignments.html';
        }
      } catch (err) {
        let message = 'Login failed';
        if (err instanceof ApiError) {
          if (err.code === 'INVALID_CREDENTIALS') {
            message = 'Wrong email or password';
          } else {
            message = err.detail || err.message || 'Login failed';
          }
        } else if (err && err.message) {
          message = err.message;
        }

        if (bannerContainer) {
          bannerContainer.innerHTML = '';
          bannerContainer.appendChild(banner('error', message));
        }
      } finally {
        if (submitBtn) {
          submitBtn.disabled = false;
        }
      }
    });
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initLogin);
} else {
  initLogin();
}

'use strict';

document.addEventListener('DOMContentLoaded', () => {
  const nav = document.querySelector('.site-nav, .hero-top');
  if (!nav) return;
  const panel = nav.querySelector('.nav-actions, .hero-menu');
  if (!panel) return;
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'mobile-nav-toggle';
  button.textContent = 'Menu';
  panel.id = 'primary-menu';
  panel.classList.add('mobile-nav-panel');
  button.setAttribute('aria-controls', panel.id);
  button.setAttribute('aria-expanded', 'false');
  button.setAttribute('aria-label', 'Apri menu di navigazione');
  const mobile = window.matchMedia('(max-width: 920px)');
  function setOpen(open, restoreFocus = false) {
    nav.classList.toggle('navigation-open', open);
    button.setAttribute('aria-expanded', String(open));
    button.setAttribute('aria-label', open ? 'Chiudi menu di navigazione' : 'Apri menu di navigazione');
    if (restoreFocus) button.focus();
  }
  nav.insertBefore(button, panel);
  nav.classList.add('navigation-enhanced');
  button.addEventListener('click', () => setOpen(button.getAttribute('aria-expanded') !== 'true'));
  nav.addEventListener('keydown', event => {
    if (event.key === 'Escape' && button.getAttribute('aria-expanded') === 'true') {
      setOpen(false, true);
    }
  });
  panel.addEventListener('click', event => {
    if (mobile.matches && event.target.closest('a')) setOpen(false);
  });
  mobile.addEventListener('change', () => {
    const focusInside = panel.contains(document.activeElement);
    setOpen(false, mobile.matches && focusInside);
  });
});

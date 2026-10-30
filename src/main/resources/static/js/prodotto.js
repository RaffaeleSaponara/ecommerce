'use strict';

document.addEventListener('DOMContentLoaded', () => {
  const main = document.getElementById('product-main-image');
  const thumbnails = document.querySelectorAll('.detail-thumbnail');
  thumbnails.forEach(button => button.addEventListener('click', () => {
    const url = button.dataset.imageUrl;
    if (!/^\/media\/products\/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\.png$/.test(url)) return;
    main.src = url;
    thumbnails.forEach(other => other.setAttribute('aria-pressed', String(other === button)));
  }));
  main.addEventListener('error', () => {
    if (!main.src.endsWith('/images/product-placeholder.svg')) main.src = '/images/product-placeholder.svg';
  });
  const button = document.getElementById('detail-add-cart');
  const status = document.getElementById('detail-cart-status');
  button.addEventListener('click', async () => {
    if (button.disabled) return;
    button.disabled = true;
    status.textContent = 'Aggiunta al carrello…';
    try {
      const token = document.querySelector('meta[name="_csrf"]')?.content;
      const header = document.querySelector('meta[name="_csrf_header"]')?.content;
      if (!token || !header) throw new Error('Token di sicurezza assente. Ricarica la pagina.');
      if (!/^\d+$/.test(button.dataset.productId)) throw new Error('Prodotto non valido.');
      const response = await fetch('/cart/add', {method: 'POST', credentials: 'same-origin',
        headers: {'Content-Type': 'application/json', [header]: token}, body: button.dataset.productId});
      if (response.status === 401 || response.redirected) throw new Error('Accedi per aggiungere il prodotto al carrello.');
      if (response.status === 403) throw new Error('Sessione non valida. Ricarica la pagina.');
      if (!response.ok) throw new Error('Impossibile aggiungere il prodotto. Riprova.');
      status.textContent = 'Prodotto aggiunto al carrello.';
    } catch (error) {
      status.textContent = error.message || 'Connessione assente. Riprova.';
    } finally {
      button.disabled = false;
    }
  });
});

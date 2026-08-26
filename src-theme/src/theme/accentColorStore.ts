import { writable } from 'svelte/store';

function isValidHex(hex: string | null): boolean {
  return typeof hex === 'string' && /^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$/.test(hex);
}

function getAccentColor(): string {
  try {
    const legacy = localStorage.getItem('lb_accentColor');
    const current = localStorage.getItem('clickgui.color');
    const color = isValidHex(current) ? current : legacy;
    return isValidHex(color) ? color! : '#1e90ff';
  } catch {
    return '#1e90ff';
  }
}

const accentColorStore = writable('#1e90ff');

accentColorStore.subscribe(color => {
  if (typeof document !== 'undefined') {
    document.documentElement.style.setProperty('--accent-color', hexToRgb(color));
  }
});

function initAccentColorStore() {
  accentColorStore.set(getAccentColor());
}

if (document.readyState === 'complete' || document.readyState === 'interactive') {
  initAccentColorStore();
} else {
  window.addEventListener('DOMContentLoaded', initAccentColorStore);
}

window.addEventListener('storage', () => {
  accentColorStore.set(getAccentColor());
});

export function setAccentColor(color: string) {
  if (!isValidHex(color)) return;
  localStorage.setItem('lb_accentColor', color);
  localStorage.setItem('clickgui.color', color);
  accentColorStore.set(color);
  document.documentElement.style.setProperty('--accent-color', hexToRgb(color));
}

function hexToRgb(hex: string): string {
  const value = hex.replace('#', '');
  const expanded = value.length === 3 ? value.split('').map(c => c + c).join('') : value;
  return `${parseInt(expanded.slice(0, 2), 16)}, ${parseInt(expanded.slice(2, 4), 16)}, ${parseInt(expanded.slice(4, 6), 16)}`;
}

export { accentColorStore };

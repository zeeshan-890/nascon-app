// Pure money helpers. All amounts are integer minor units (paisa).

function toMs(v) {
  return v instanceof Date ? v.getTime() : new Date(v).getTime();
}

// Latest schedule with effective_from <= now, else the variant's base price.
function effectivePrice(variant, schedules, now) {
  const nowMs = toMs(now);
  let best = null;
  for (const s of schedules || []) {
    const at = toMs(s.effective_from);
    if (at <= nowMs && (best === null || at > best.at)) best = { at, price: s.price_minor };
  }
  return best ? best.price : variant.price_minor;
}

function computeTotals(items, discountPercent) {
  let subtotal = 0;
  for (const it of items) subtotal += it.quantity * it.unit_price_minor;
  const pct = discountPercent || 0;
  const discount = Math.floor((subtotal * pct) / 100);
  return { subtotal_minor: subtotal, discount_minor: discount, total_minor: subtotal - discount };
}

function formatPkr(minor) {
  const major = Math.floor(minor / 100);
  const rest = String(minor % 100).padStart(2, '0');
  return `PKR ${major}.${rest}`;
}

module.exports = { effectivePrice, computeTotals, formatPkr };

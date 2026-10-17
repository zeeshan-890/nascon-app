const PDFDocument = require('pdfkit');
const { formatPkr } = require('./pricing');

// The PDF uses a built-in Latin-1 font: anything else becomes '?' (the JSON keeps the original).
function sanitize(text) {
  let out = '';
  for (const ch of String(text)) {
    const c = ch.codePointAt(0);
    out += (c >= 0x20 && c <= 0x7e) || (c >= 0xa0 && c <= 0xff) ? ch : '?';
  }
  return out;
}

const CUSTOMER_MAX = 120;

function receiptLines(payload) {
  const customer = sanitize(payload.customer_id).slice(0, CUSTOMER_MAX);
  const lines = ['Receipt', `Order: ${sanitize(payload.order_id)}`, `Customer: ${customer}`];
  for (const i of payload.items) {
    lines.push(`${sanitize(i.variant_id)} x${i.quantity} @ ${formatPkr(i.unit_price_minor)}`);
  }
  lines.push(`Subtotal: ${formatPkr(payload.subtotal_minor)}`);
  lines.push(`Discount: ${formatPkr(payload.discount_minor)}`);
  lines.push(`Total: ${formatPkr(payload.total_minor)}`);
  lines.push(`Paid at: ${payload.paid_at}`);
  return lines;
}

// Resolves to a Buffer holding the PDF. Each line is one text call with no wrapping.
function generateReceiptPdf(payload) {
  return new Promise((resolve, reject) => {
    const doc = new PDFDocument({ size: 'A4', margin: 40, compress: false });
    const chunks = [];
    doc.on('data', (c) => chunks.push(c));
    doc.on('end', () => resolve(Buffer.concat(chunks)));
    doc.on('error', reject);
    try {
      doc.font('Courier').fontSize(10);
      let y = 60;
      for (const line of receiptLines(payload)) {
        doc.text(line, 40, y, { lineBreak: false });
        y += 16;
      }
      doc.end();
    } catch (err) {
      reject(err);
    }
  });
}

module.exports = { generateReceiptPdf, receiptLines, sanitize };

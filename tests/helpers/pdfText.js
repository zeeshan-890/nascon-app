const pdfParse = require('../../backend/node_modules/pdf-parse');

// pdf-parse bundles pdf.js 1.x, which rejects some perfectly valid files depending on their total
// length ("bad XRef entry"). Trailing bytes after %%EOF are ignored by every PDF reader, so retry
// with a few newline paddings. Only used by tests.
async function extractPdfText(buf) {
  let lastErr;
  for (let pad = 0; pad < 8; pad += 1) {
    try {
      return (await pdfParse(Buffer.concat([buf, Buffer.alloc(pad, 10)]))).text;
    } catch (err) {
      lastErr = err;
    }
  }
  throw lastErr;
}

module.exports = { extractPdfText };

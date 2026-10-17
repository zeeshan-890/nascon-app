const config = require('../config');
const logger = require('../logger');
const pool = require('../db/pool');
const store = require('../repos/store');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function minioClient() {
  const Minio = require('minio');
  return new Minio.Client({
    endPoint: config.minio.endpoint, port: config.minio.port, useSSL: false,
    accessKey: config.minio.accessKey, secretKey: config.minio.secretKey,
  });
}

// Uploads the stored receipt PDF to receipts/{order_id}.pdf. Idempotent (same key overwritten).
async function uploadReceipt(orderId) {
  const b64 = await store.receiptPdfBase64(pool, orderId);
  if (!b64) return false;
  const key = `receipts/${orderId}.pdf`;
  await minioClient().putObject(config.minio.bucket, key, Buffer.from(b64, 'base64'), {
    'Content-Type': 'application/pdf',
  });
  await store.setReceiptKey(pool, orderId, key);
  return true;
}

// Retries with backoff; after `attempts` failures logs and gives up (caller acks).
async function uploadWithRetry(orderId, { attempts = 5, baseMs = 500 } = {}) {
  for (let i = 1; i <= attempts; i++) {
    try {
      return await uploadReceipt(orderId);
    } catch (err) {
      if (i === attempts) {
        logger.error({ err: err.message, order_id: orderId }, 'receipt upload failed; giving up');
        return false;
      }
      await sleep(baseMs * 2 ** (i - 1));
    }
  }
  return false;
}

module.exports = { uploadReceipt, uploadWithRetry };

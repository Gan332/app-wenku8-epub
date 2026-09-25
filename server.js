'use strict';

const path = require('node:path');
const { createApp } = require('./src/app');

const port = Number(process.env.PORT || 3210);
const host = process.env.HOST || '127.0.0.1';
const app = createApp();
const server = app.listen(port, host, () => {
  console.log('\n  Wenku8 EPUB Studio');
  console.log(`  UI:     http://${host}:${port}`);
  console.log(`  OUTPUT: ${path.join(__dirname, 'output')}`);
  console.log('  Press Ctrl+C to stop.\n');
});

server.requestTimeout = 300_000;
server.headersTimeout = 65_000;
server.keepAliveTimeout = 10_000;

function shutdown() {
  console.log('\n正在关闭服务…');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
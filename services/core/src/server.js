import http from 'node:http';

const port = Number(process.env.PORT || 8080);
const version = '0.1.0';

const server = http.createServer((req, res) => {
  const path = req.url?.split('?')[0] ?? '/';
  res.setHeader('content-type', 'application/json; charset=utf-8');

  if (req.method === 'GET' && path === '/api/v1/health') {
    res.writeHead(200);
    res.end(JSON.stringify({ status: 'ok', service: 'fad-core', version }));
    return;
  }

  res.writeHead(404);
  res.end(JSON.stringify({ error: 'not_found' }));
});

server.listen(port, '0.0.0.0', () => {
  console.log(`fad-core listening on ${port}`);
});

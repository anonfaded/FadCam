import { serve } from '@hono/node-server'; import { Hono } from 'hono';
const app=new Hono(); const port=Number(process.env.PORT||8082);
app.get('/health',c=>c.json({service:'fad-production',status:'ok'}));
app.get('/api/v1/productions',c=>c.json({items:[]}));
serve({fetch:app.fetch,port});

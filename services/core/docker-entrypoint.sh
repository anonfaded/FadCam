#!/bin/sh
set -eu
node -e "const {Client}=require('pg'); const fs=require('fs'); (async()=>{const c=new Client({connectionString:process.env.DATABASE_URL}); await c.connect(); await c.query(fs.readFileSync('/app/db/001_initial.sql','utf8')); await c.end()})().catch(e=>{console.error(e);process.exit(1)})"
exec npm start

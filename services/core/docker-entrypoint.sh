#!/bin/sh
set -eu

node src/migrate.mjs
exec npm start

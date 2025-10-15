// JavaScript example with in-memory dependency for GraalFaaS
// Uses a lightweight CommonJS-style require injected by the host.
const { greet } = require('greeter');

function handler(event) {
  const name = event && event.name != null ? event.name : "World";
  return { message: greet(name) };
}

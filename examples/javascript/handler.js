// JavaScript example function for GraalFaaS
// Contract: export a global function named `handler(event)` that returns a JSON-serializable value.
function handler(event) {
  const name = event && event.name != null ? event.name : "World";
  return { message: `Hello, ${name}!` };
}

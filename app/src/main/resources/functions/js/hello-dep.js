const { greet } = require('greeter');
function handler(event) {
  return { message: greet(event.name) };
}

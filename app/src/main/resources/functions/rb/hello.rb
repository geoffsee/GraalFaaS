def handler(event)
  name = event['name'] || 'World'
  { 'message' => "Hello, #{name}!" }
end

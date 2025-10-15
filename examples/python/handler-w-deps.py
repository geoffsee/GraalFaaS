# Python example with an in-memory dependency for GraalFaaS
# The host preloads dependencies as modules (see manifest) so we can import them normally.
from greeter import greet

def handler(event):
    try:
        name = event.get("name") if isinstance(event, dict) else None
    except Exception:
        name = None
    return {"message": greet(name)}

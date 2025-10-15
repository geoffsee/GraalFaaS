# Python example function for GraalFaaS
# Contract: define a function `handler(event)` that returns a JSON-serializable value.

def handler(event):
    try:
        name = event.get("name", "World") if isinstance(event, dict) else "World"
    except Exception:
        name = "World"
    return {"message": f"Hello, {name}!"}

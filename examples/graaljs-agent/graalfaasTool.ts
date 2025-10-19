import { tool } from "@openai/agents";
import { z } from "zod";

// Logging helper
function log(level: string, message: string, data?: any) {
  const timestamp = new Date().toISOString();
  const logMsg = `[${timestamp}] [${level}] [graalfaasTool] ${message}`;
  if (level === "ERROR" || level === "WARN") {
    console.error(logMsg, data ? JSON.stringify(data) : "");
  } else {
    console.log(logMsg, data ? JSON.stringify(data) : "");
  }
}

/**
 * A minimal tool that invokes a GraalFaaS function by ID over HTTP.
 *
 * Preconditions:
 * - The GraalFaaS server is running (e.g., ./gradlew run --args="serve --port 8080").
 * - The function has been uploaded to the server's storage and is addressable by {functionId}.
 *
 * This is intentionally simple and avoids writing to the server's storage from the agent.
 */
export function createGraalFaasInvokeTool() {
  log("INFO", "Creating GraalFaaS invoke tool");
  return tool({
    name: "graalfaas_invoke",
    description: "Invoke a GraalFaaS function by its server-assigned UUIDv7 id to execute code safely in an isolated environment.", 
    parameters: z.object({
      serverUrl: z.string().describe("Base URL of the GraalFaaS server"),
      functionId: z.string().describe("The ID of the function to invoke"),
      event: z.object({
        name: z.string().describe("Name parameter for the event"),
      }).describe("Event payload to pass to the function"),
    }),
    execute: async (args) => {
      const invocationId = `inv-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
      log("INFO", `[${invocationId}] Tool invoked`, { functionId: args.functionId });

      const envUrl = (typeof process !== "undefined" && process.env && process.env.GRAALFAAS_URL) || undefined;
      const serverUrl = (args.serverUrl || envUrl || "http://localhost:8080").replace(/\/$/, "");
      const url = `${serverUrl}/invoke/${encodeURIComponent(args.functionId)}`;
      log("INFO", `[${invocationId}] Invoking function`, { url, functionId: args.functionId });

      // Optional sanity check for UUIDv7 format
      const uuidV7Regex = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
      if (!uuidV7Regex.test(args.functionId)) {
        log("WARN", `[${invocationId}] functionId does not look like a UUIDv7`, { functionId: args.functionId });
      }

      try {
        const startTime = Date.now();
        const res = await fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: args.event ? JSON.stringify(args.event) : "{}",
        } as RequestInit);
        const fetchDuration = Date.now() - startTime;

        const text = await res.text();
        log("INFO", `[${invocationId}] Received response`, {
          status: res.status,
          duration: `${fetchDuration}ms`,
          responseLength: text.length
        });

        if (!res.ok) {
          log("WARN", `[${invocationId}] Function invocation failed`, { status: res.status, response: text });
          return `GraalFaaS error (${res.status}): ${text}`;
        }

        log("INFO", `[${invocationId}] Function invoked successfully`);
        return text;
      } catch (err) {
        log("ERROR", `[${invocationId}] Failed to reach GraalFaaS`, { url, error: String(err) });
        return `Failed to reach GraalFaaS at ${url}: ${String(err)}`;
      }
    },
  });
}

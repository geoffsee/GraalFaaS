import { tool } from "@openai/agents";
import { z } from "zod";

// Logging helper
function log(level: string, message: string, data?: any) {
  const timestamp = new Date().toISOString();
  const logMsg = `[${timestamp}] [${level}] [graalfaasCreateTool] ${message}`;
  if (level === "ERROR" || level === "WARN") {
    console.error(logMsg, data ? JSON.stringify(data) : "");
  } else {
    console.log(logMsg, data ? JSON.stringify(data) : "");
  }
}

/**
 * Tool to create/upload a function on a GraalFaaS server using the POST /functions endpoint.
 * Use this to provision a function before invoking it with graalfaas_invoke.
 */
export function createGraalFaasCreateTool() {
  log("INFO", "Creating GraalFaaS create function tool");
  return tool({
    name: "graalfaas_create_function",
    description: "Create a new function on the GraalFaaS server.",
    parameters: z.object({
      serverUrl: z.string().describe("Base URL of the GraalFaaS server"),
      id: z.string().describe("Unique function ID"),
      languageId: z.enum(["js", "python"]).describe("Programming language"),
      functionName: z.string().describe("Handler function name"),
      source: z.string().describe("Source code for the function"),
    }),
    execute: async (args) => {
      const creationId = `create-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
      log("INFO", `[${creationId}] Tool invoked`, {
        id: args.id,
        languageId: args.languageId,
        functionName: args.functionName,
        hasSource: !!args.source,
        hasServerUrl: !!args.serverUrl
      });

      const envUrl = (typeof process !== "undefined" && process.env && process.env.GRAALFAAS_URL) || undefined;
      const serverUrl = (args.serverUrl || envUrl || "http://localhost:8080").replace(/\/$/, "");

      const manifest: Record<string, any> = {
        id: args.id,
        languageId: args.languageId,
        functionName: args.functionName,
        source: args.source,
      };

      log("INFO", `[${creationId}] Creating function`, {
        id: manifest.id,
        languageId: manifest.languageId,
        functionName: manifest.functionName
      });

      const url = `${serverUrl}/functions`;
      try {
        const startTime = Date.now();
        const res = await fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(manifest),
        } as RequestInit);
        const fetchDuration = Date.now() - startTime;

        const text = await res.text();
        log("INFO", `[${creationId}] Received response`, {
          status: res.status,
          duration: `${fetchDuration}ms`,
          responseLength: text.length
        });

        if (!res.ok) {
          log("WARN", `[${creationId}] Function creation failed`, { status: res.status, response: text });
          return `GraalFaaS error (${res.status}): ${text}`;
        }

        log("INFO", `[${creationId}] Function created successfully`, { id: manifest.id });
        return text;
      } catch (err) {
        log("ERROR", `[${creationId}] Failed to reach GraalFaaS`, { url, error: String(err) });
        return `Failed to reach GraalFaaS at ${url}: ${String(err)}`;
      }
    },
  });
}

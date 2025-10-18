#!/usr/bin/env bun

import {Agent} from "@openai/agents";
import {runAgent} from "./agent";
import { createGraalFaasInvokeTool } from "./graalfaasTool";
import { createGraalFaasCreateTool } from "./graalfaasCreateTool";

// Logging helper
function log(level: string, message: string, data?: any) {
  const timestamp = new Date().toISOString();
  const logMsg = `[${timestamp}] [${level}] ${message}`;
  if (level === "ERROR" || level === "WARN") {
    console.error(logMsg, data ? JSON.stringify(data) : "");
  } else {
    console.log(logMsg, data ? JSON.stringify(data) : "");
  }
}

// Optional env configuration for a realistic tool demo
const SERVER_URL = process.env.GRAALFAAS_URL || "http://localhost:8080";
const FUNCTION_ID = process.env.GRAALFAAS_FUNCTION_ID; // e.g., "hello-js" uploaded to the server
log("INFO", "Initializing GraalFaaS Agent", { serverUrl: SERVER_URL, functionId: FUNCTION_ID });

log("INFO", "Creating agent with GraalFaaS tools");
const agent = new Agent({
    name: 'Assistant',
    instructions: `You are an engineering assistant that helps execute code safely using GraalFaaS.

Use graalfaas_create_function to create functions and graalfaas_invoke to run them.
Be concise in your responses and always show the tool results.

When creating a JavaScript function, use this format:
- id: unique identifier (e.g., "hello-js")
- languageId: "js"
- functionName: "handler" (or specify a custom name)
- source: the JavaScript function code

Example JavaScript hello function:
function handler(event) {
  return { message: "Hello, " + event.name + "!" };
}

Example Python hello function:
def handler(event):
  return {"message": f"Hello, {event['name']}!"}

Always provide ALL required fields when creating functions: serverUrl, id, languageId, functionName, and source.`,
    model: "gpt-4.1-mini",
    tools: [createGraalFaasCreateTool(), createGraalFaasInvokeTool()],
});
log("INFO", "Agent created successfully");

// Build a real-world task that prompts the model to use the tool.
const userTask = FUNCTION_ID
  ? `Invoke function "${FUNCTION_ID}" at ${SERVER_URL}. The event parameter must be {"name":"Agent Demo"}.`
  : `Create a JavaScript hello function at ${SERVER_URL} with id "hello-js", then invoke it. When invoking, the event parameter must be {"name":"Agent Demo"}.`;

log("INFO", "Starting agent execution", { taskType: FUNCTION_ID ? "invoke-only" : "create-and-invoke" });
const startTime = Date.now();
const result = await runAgent(agent, userTask, {});
const duration = Date.now() - startTime;
log("INFO", `Agent execution completed in ${duration}ms`);

log("INFO", "Processing agent response stream");
const stream = result.toStream()

const nonModelMessages = [] as any[];
const textParts: string[] = [];
let chunkCount = 0;

function streamPart(part: string) {
    textParts.push(part);
    process.stdout.write(part);
}

for await (const chunk of stream) {
    chunkCount++;
    if (chunk.data?.type === "output_text_delta" && (chunk as any).data.delta) {
        streamPart((chunk as any).data.delta);
    } else {
        nonModelMessages.push((chunk as any).data);
    }
}

log("INFO", "Stream processing completed", {
    textParts: textParts.length,
    nonModelMessagesCount: nonModelMessages.length,
    totalChunks: chunkCount
});
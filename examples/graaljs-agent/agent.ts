import {Agent, RunContext ,run, AgentInputItem, RunState, StreamRunOptions} from '@openai/agents';


export type AgentType = {};

export type ContextType = {}

export async function runAgent<AgentType extends Agent<any, any>, RunContext>(agent: Agent, instructions: string | AgentInputItem[] | RunState<RunContext, Agent<unknown, "text">>, options: StreamRunOptions<RunContext>) {
    return await run<Agent, RunContext>(
        agent,
        instructions,
        {...options, stream: true}
    );
}




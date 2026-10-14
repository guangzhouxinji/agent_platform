import test from 'node:test';import assert from 'node:assert/strict';
import {acquireExecutionFence} from '../../../../scripts/lib/single-database-initialization-fence.mjs';
for(const [name,c] of [
 ['existing environment',{manifest:{mode:'existing'}}],
 ['apps have started',{manifest:{mode:'empty'},approval:{applicationsNeverStarted:false}}],
 ['registered product writer',{manifest:{mode:'empty'},approval:{applicationsNeverStarted:true},deployment:{domains:[{writerContainerIds:['a'.repeat(64)]}]}}]
])test(name+' rejected before Docker or a lock',()=>assert.rejects(acquireExecutionFence(c,[]),/EXACT_NEVER_STARTED_INITIALIZATION_REQUIRED/));

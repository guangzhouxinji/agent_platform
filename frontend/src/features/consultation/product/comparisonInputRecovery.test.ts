import {beforeEach,expect,it,vi} from 'vitest';
import {ComparisonInputRecovery} from './comparisonInputRecovery';
beforeEach(()=>vi.stubGlobal('crypto',{randomUUID:()=> 'stable-request'}));
it('keeps immutable packet for unknown submission and refuses another write',()=>{const r=new ComparisonInputRecovery(),body={version:1},p=r.begin('PREPARE','/prepare',body);body.version=2;expect(JSON.parse(p.bodyJson).version).toBe(1);r.reject(p,new Error('network'));expect(r.pending).toBe(p);expect(()=>r.begin('CREATE','/new',{})).toThrow('对账');expect(r.confirm(p)).toBe(true);});
it.each([0,408,429,500,503])('retains unknown intent %s',status=>{const r=new ComparisonInputRecovery(),p=r.begin('CREATE','/input',{});r.reject(p,{status});expect(r.pending).toBe(p);});
it.each([400,401,403,404,409,422])('releases confirmed rejection %s',status=>{const r=new ComparisonInputRecovery(),p=r.begin('CONFIRM','/feature',{});r.reject(p,{status});expect(r.pending).toBeNull();});
it('old confirmation cannot clear a newer intent',()=>{const r=new ComparisonInputRecovery(),p=r.begin('PREPARE','/prepare',{});r.clear();const q=r.begin('CREATE','/input',{});expect(r.confirm(p)).toBe(false);r.reject(p,{status:400});expect(r.pending).toBe(q);});

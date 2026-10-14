import {act,cleanup,fireEvent,render,screen} from '@testing-library/react';
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import ProductTrademarkWorkspace from './ProductTrademarkWorkspace';
import {queryTrademarks,trademarkDetail} from './api';
import {initialQuery,type Observation} from './types';

const fetchMock=vi.fn<typeof fetch>();
const record=(i:number):Observation=>({ref:{sourceId:'source',recordId:'r'+i,version:'RAW_SHA256:'+i},registrationNumber:i===0?'00012A':'number-'+i,name:'商标 '+i,imageRef:null,
  applicants:['甲','乙'],address:'地址',classes:[{code:'09',name:'类别九',items:['第一项','第二项'],similarGroups:['0901']},{code:'42',name:'类别四十二',items:['第三项'],similarGroups:['4201']}],
  registered:{raw:'2024-02-29',precision:'DAY',day:'2024-02-29'},validUntil:{raw:'2034-02',precision:'MONTH',day:null},sourceStatus:'原始来源状态',displayStatus:'UNMAPPED',sourceUpdatedAt:'原始更新时间',sourceRef:'source-ref'});
const provenance=()=>({source:{sourceId:'source',version:'dataset-v1'},sourceType:'APPROVED_SUBSTITUTE',evidenceRef:'manifest',actualScope:'CURRENT_AUTHORIZED_CAPTURE',sourceUpdatedAt:null,fetchedAt:new Date().toISOString(),cacheState:'MISS' as const,cachedAt:null});
const page=(number=1)=>({items:Array.from({length:number===1?20:1},(_,i)=>record((number-1)*20+i)),pagination:{request:{page:number,size:20,cursor:null},reliableTotal:21,hasNext:number===1,nextCursor:null},sort:'REGISTRATION_DATE_DESC_NULLS_LAST_SOURCE_RECORD_ASC',provenance:[provenance()]});
const detail=()=>({item:record(0),capabilities:{CHANGES:'UNKNOWN',OPPOSITIONS:'NOT_PROVIDED',INVALIDATIONS:'UNKNOWN'},provenance:provenance()});
const view=(data:unknown,task='task')=>({taskId:task,taskVersion:1,expiresAt:new Date(Date.now()+60000).toISOString(),data});
const response=(data:unknown,status=200)=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json'}});
const props=()=>({token:'real-session-input',taskId:'task',onBack:vi.fn(),onSessionExpired:vi.fn()});
function submit(){fireEvent.click(screen.getByRole('button',{name:'查询'}));}
beforeEach(()=>{vi.stubGlobal('fetch',fetchMock);fetchMock.mockReset();});
afterEach(()=>{cleanup();vi.unstubAllGlobals();vi.useRealTimers();});

describe('trademark product workspace using transport with isolated HTTP responses',()=>{
  it('queries with the exact Task, pages reliably and retains filters on detail return',async()=>{
    fetchMock.mockImplementation(async(input)=>{const url=String(input);return response(view(url.includes('/records/')?detail():page(new URL(url,'https://app.invalid').searchParams.get('page')==='2'?2:1)));});
    render(<ProductTrademarkWorkspace {...props()}/>);
    fireEvent.change(screen.getByLabelText('注册号'),{target:{value:'00012A'}});submit();
    await screen.findByText('共 21 条');
    expect(String(fetchMock.mock.calls[0][0])).toContain('/tasks/task/trademarks?');expect(String(fetchMock.mock.calls[0][0])).toContain('registrationNumber=00012A');
    expect(fetchMock.mock.calls[0][1]).toMatchObject({cache:'no-store',headers:{Authorization:'Bearer real-session-input'}});
    fireEvent.click(screen.getByRole('button',{name:'商标 0'}));await screen.findByRole('region',{name:'商标详情'});
    expect(screen.getByText('00012A')).toBeInTheDocument();expect(screen.getByText('第三项')).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[1][0])).toContain('version=RAW_SHA256%3A0');
    fireEvent.click(screen.getByRole('button',{name:'返回查询列表'}));expect(screen.getByLabelText('注册号')).toHaveValue('00012A');
    fireEvent.click(screen.getByRole('button',{name:'下一页'}));await screen.findByText('第 2 页');await screen.findByRole('button',{name:'商标 20'});
    expect(screen.getByRole('button',{name:'下一页'})).toBeDisabled();
  });
  it('clears prior data on failure and never presents unavailable as zero matches',async()=>{
    fetchMock.mockResolvedValueOnce(response(view(page()))).mockResolvedValueOnce(response({},503));render(<ProductTrademarkWorkspace {...props()}/>);submit();await screen.findByText('共 21 条');
    fireEvent.click(screen.getByRole('button',{name:'重新核验来源'}));await screen.findByRole('alert');expect(screen.queryByText('共 21 条')).not.toBeInTheDocument();expect(screen.queryByText('当前条件下没有匹配商标。')).not.toBeInTheDocument();
  });
  it('aborts and ignores a delayed old Task response after switching Task',async()=>{
    let resolve!:(response:Response)=>void;fetchMock.mockImplementationOnce(()=>new Promise<Response>(done=>{resolve=done;}));
    const p=props();const rendered=render(<ProductTrademarkWorkspace {...p}/>);submit();const signal=fetchMock.mock.calls[0][1]?.signal;
    rendered.rerender(<ProductTrademarkWorkspace {...p} taskId="new-task"/>);expect(signal?.aborted).toBe(true);
    await act(async()=>{resolve(response(view(page())));});expect(screen.queryByText('共 21 条')).not.toBeInTheDocument();
  });
  it('notifies the parent once on expired session and clears the result',async()=>{
    fetchMock.mockResolvedValue(response({},401));const p=props();render(<ProductTrademarkWorkspace {...p}/>);submit();await screen.findByRole('alert');expect(p.onSessionExpired).toHaveBeenCalledTimes(1);
  });
  it('rejects another Task envelope and malformed pagination without showing data',async()=>{
    fetchMock.mockResolvedValueOnce(response(view(page(),'other')));render(<ProductTrademarkWorkspace {...props()}/>);submit();await screen.findByRole('alert');expect(screen.queryByText('共 21 条')).not.toBeInTheDocument();
    fetchMock.mockResolvedValueOnce(response(view({...page(),pagination:{...page().pagination,reliableTotal:1}})));
    await expect(queryTrademarks('token','task',initialQuery(),new AbortController().signal)).rejects.toThrow('来源数据未通过校验');
  });
  it('clears displayed content at the server supplied earliest expiry',async()=>{
    vi.useFakeTimers();fetchMock.mockResolvedValue(response({...view(page()),expiresAt:new Date(Date.now()+1000).toISOString()}));render(<ProductTrademarkWorkspace {...props()}/>);
    await act(async()=>{submit();await Promise.resolve();});expect(screen.getByText('共 21 条')).toBeInTheDocument();
    await act(async()=>{vi.advanceTimersByTime(1001);});expect(screen.queryByText('共 21 条')).not.toBeInTheDocument();expect(screen.getByRole('alert')).toHaveTextContent('可访问期限已到');
  });
  it('returns to the original Task through the supplied navigation callback',()=>{
    const p=props();render(<ProductTrademarkWorkspace {...p}/>);fireEvent.click(screen.getByRole('button',{name:'返回任务'}));expect(p.onBack).toHaveBeenCalledTimes(1);
  });
});

describe('trademark filter, exact-version and current-display boundaries',()=>{
  it('sends trimmed business filters with repeated classes, fixed paging and refresh',async()=>{
    fetchMock.mockResolvedValue(response(view(page(2))));
    const query={...initialQuery(),page:2,registrationNumber:' 00012A ',name:' 甲商标 ',applicant:' 甲 ',categories:'09， 42',statuses:' UNMAPPED ',
      registeredFrom:'2024-01-01',registeredTo:'2024-12-31',validFrom:'2030-01-01',validTo:'2035-12-31'};
    await queryTrademarks('token','task',query,new AbortController().signal,true);
    const url=new URL(String(fetchMock.mock.calls[0][0]),'https://app.invalid');
    expect(url.searchParams.get('registrationNumber')).toBe('00012A');expect(url.searchParams.get('name')).toBe('甲商标');
    expect(url.searchParams.get('applicant')).toBe('甲');expect(url.searchParams.getAll('categories')).toEqual(['09','42']);
    expect(url.searchParams.getAll('statuses')).toEqual(['UNMAPPED']);expect(url.searchParams.get('registeredFrom')).toBe('2024-01-01');
    expect(url.searchParams.get('registeredTo')).toBe('2024-12-31');expect(url.searchParams.get('validFrom')).toBe('2030-01-01');
    expect(url.searchParams.get('validTo')).toBe('2035-12-31');expect(url.searchParams.get('page')).toBe('2');expect(url.searchParams.get('refresh')).toBe('true');
    expect(fetchMock.mock.calls[0][1]?.headers).toMatchObject({'X-Request-Id':expect.any(String)});
  });
  it.each(['short-page','wrong-page','false-next'])('refuses inconsistent reliable pagination: %s',async fault=>{
    const result=page();
    if(fault==='short-page')result.items.pop();
    if(fault==='wrong-page')result.pagination.request.page=2;
    if(fault==='false-next')result.pagination.hasNext=false;
    fetchMock.mockResolvedValue(response(view(result)));
    await expect(queryTrademarks('token','task',initialQuery(),new AbortController().signal)).rejects.toMatchObject({status:502});
  });
  it.each(['source','record','version'])('refuses a detail which substitutes the requested %s',async coordinate=>{
    const result=detail();const expected={...result.item.ref};
    if(coordinate==='source')result.item.ref.sourceId='other-source';
    if(coordinate==='record')result.item.ref.recordId='other-record';
    if(coordinate==='version')result.item.ref.version='other-version';
    fetchMock.mockResolvedValue(response(view(result)));
    await expect(trademarkDetail('token','task',expected,new AbortController().signal)).rejects.toMatchObject({status:502});
  });
  it.each([401,403])('permission failure %s clears an already displayed result',async status=>{
    fetchMock.mockResolvedValueOnce(response(view(page()))).mockResolvedValueOnce(response({},status));
    const p=props();render(<ProductTrademarkWorkspace {...p}/>);submit();await screen.findByText('共 21 条');
    fireEvent.click(screen.getByRole('button',{name:'重新核验来源'}));await screen.findByRole('alert');
    expect(screen.queryByRole('region',{name:'商标查询结果'})).not.toBeInTheDocument();
    expect(screen.queryByText('当前条件下没有匹配商标。')).not.toBeInTheDocument();expect(p.onSessionExpired).toHaveBeenCalledTimes(status===401?1:0);
  });
  it('changing the token clears results and rejects the old pending detail',async()=>{
    let resolve!:(response:Response)=>void;
    fetchMock.mockResolvedValueOnce(response(view(page()))).mockImplementationOnce(()=>new Promise<Response>(done=>{resolve=done;}));
    const p=props(),rendered=render(<ProductTrademarkWorkspace {...p}/>);submit();await screen.findByText('共 21 条');
    fireEvent.click(screen.getByRole('button',{name:'商标 0'}));const signal=fetchMock.mock.calls[1][1]?.signal;
    rendered.rerender(<ProductTrademarkWorkspace {...p} token="new-session"/>);expect(signal?.aborted).toBe(true);
    expect(screen.queryByText('共 21 条')).not.toBeInTheDocument();await act(async()=>resolve(response(view(detail()))));
    expect(screen.queryByRole('region',{name:'商标详情'})).not.toBeInTheDocument();
  });
  it('the earlier list expiry clears a later-lived open detail',async()=>{
    vi.useFakeTimers();const now=Date.now();
    fetchMock.mockResolvedValueOnce(response({...view(page()),expiresAt:new Date(now+1000).toISOString()}))
      .mockResolvedValueOnce(response({...view(detail()),expiresAt:new Date(now+60000).toISOString()}));
    render(<ProductTrademarkWorkspace {...props()}/>);await act(async()=>{submit();await Promise.resolve();});
    await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'商标 0'}));await Promise.resolve();});
    expect(screen.getByRole('region',{name:'商标详情'})).toBeInTheDocument();
    await act(async()=>{vi.advanceTimersByTime(1001);});
    expect(screen.queryByRole('region',{name:'商标详情'})).not.toBeInTheDocument();expect(screen.queryByText('共 21 条')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('可访问期限已到');
  });
  it('returning to the Task cancels an outstanding detail and clears the visible list',async()=>{
    let resolve!:(response:Response)=>void;
    fetchMock.mockResolvedValueOnce(response(view(page()))).mockImplementationOnce(()=>new Promise<Response>(done=>{resolve=done;}));
    const p=props();render(<ProductTrademarkWorkspace {...p}/>);submit();await screen.findByText('共 21 条');
    fireEvent.click(screen.getByRole('button',{name:'商标 0'}));fireEvent.click(screen.getByRole('button',{name:'返回任务'}));
    expect(p.onBack).toHaveBeenCalledOnce();expect(fetchMock.mock.calls[1][1]?.signal?.aborted).toBe(true);
    await act(async()=>resolve(response(view(detail()))));expect(screen.queryByRole('region',{name:'商标详情'})).not.toBeInTheDocument();
    expect(screen.queryByText('共 21 条')).not.toBeInTheDocument();
  });
});

import {z} from 'zod';

const date=z.object({raw:z.string(),precision:z.string(),day:z.string().nullable()});
export const refSchema=z.object({sourceId:z.string().min(1),recordId:z.string().min(1),version:z.string().min(1)});
export const observationSchema=z.object({ref:refSchema,registrationNumber:z.string().nullable(),name:z.string().nullable(),imageRef:z.string().nullable(),
  applicants:z.array(z.string()),address:z.string().nullable(),classes:z.array(z.object({code:z.string(),name:z.string().nullable(),items:z.array(z.string()),similarGroups:z.array(z.string())})),
  registered:date.nullable(),validUntil:date.nullable(),sourceStatus:z.string().nullable(),displayStatus:z.string().nullable(),sourceUpdatedAt:z.string().nullable(),sourceRef:z.string().nullable()});
const provenance=z.object({source:z.object({sourceId:z.string(),version:z.string()}),sourceType:z.string(),evidenceRef:z.string(),actualScope:z.string(),
  sourceUpdatedAt:z.string().nullable(),fetchedAt:z.string().datetime({offset:true}),cacheState:z.enum(['BYPASSED','MISS','HIT']),cachedAt:z.string().nullable()});
export const pageSchema=z.object({items:z.array(observationSchema),pagination:z.object({request:z.object({page:z.number().int().positive(),size:z.union([z.literal(10),z.literal(20),z.literal(50)]),cursor:z.null()}),reliableTotal:z.number().int().nonnegative(),hasNext:z.boolean(),nextCursor:z.null()}),sort:z.literal('REGISTRATION_DATE_DESC_NULLS_LAST_SOURCE_RECORD_ASC'),provenance:z.array(provenance).min(1)});
const capability=z.enum(['UNKNOWN','AVAILABLE','NOT_PROVIDED']);
export const detailSchema=z.object({item:observationSchema,capabilities:z.object({CHANGES:capability,OPPOSITIONS:capability,INVALIDATIONS:capability}),provenance});
export const envelope=<T extends z.ZodType>(data:T)=>z.object({taskId:z.string().min(1),taskVersion:z.number().int().positive(),expiresAt:z.string().datetime({offset:true}),data});
export type Observation=z.infer<typeof observationSchema>;
export type RecordRef=z.infer<typeof refSchema>;
export type Page=z.infer<typeof pageSchema>;
export type Detail=z.infer<typeof detailSchema>;
export type View<T>={taskId:string;taskVersion:number;expiresAt:string;data:T};
export type Query={registrationNumber:string;name:string;applicant:string;categories:string;statuses:string;registeredFrom:string;registeredTo:string;validFrom:string;validTo:string;page:number;size:10|20|50};
export const initialQuery=():Query=>({registrationNumber:'',name:'',applicant:'',categories:'',statuses:'',registeredFrom:'',registeredTo:'',validFrom:'',validTo:'',page:1,size:20});

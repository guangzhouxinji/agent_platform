export type DataMode = "SYNTHETIC_V0" | "PUBLIC_PILOT" | string;

export interface LibraryPageResponse<T> {
  dataMode: string;
  sourceVersion: string;
  limitation: string;
  fetchedAt: string;
  requestId: string;
  page: number;
  pageSize: number;
  total: number;
  items: T[];
}

export interface LibraryDetailResponse<T> {
  dataMode: string;
  sourceVersion: string;
  limitation: string;
  fetchedAt: string;
  requestId: string;
  item: T;
  historyCapabilities?: Record<string, string>;
}

export type PatentRecord = Record<string, unknown> & {
  sourceId: string;
  sourceRecordId: string;
  sourceVersion: string;
  applicationNumber?: string;
  publicationNumber?: string;
  grantPublicationNumber?: string;
  title: string;
  patentType?: string;
  applicants: string[];
  inventors: string[];
  applicationDate?: string;
  publicationDate?: string;
  grantDate?: string;
  ipcClassifications: string[];
  cpcClassifications: string[];
  sourceLegalStatus?: string;
  mappedLegalStatus?: string;
  sourceRef?: string;
  dataMode: DataMode;
  versions: Array<{ versionId: string; documentNumber?: string; documentDate?: string; kind?: string }>;
};

export type TrademarkRecord = Record<string, unknown> & {
  sourceId: string;
  sourceRecordId: string;
  sourceVersion: string;
  registrationNumber?: string;
  name: string;
  applicants: string[];
  classes: Array<{ code: string; name?: string; goodsServices: string[]; similarGroups: string[] }>;
  registrationDate?: string;
  validUntil?: string;
  sourceStatus?: string;
  displayStatus?: string;
  sourceRef?: string;
  dataMode: DataMode;
};

export type CaseRecord = Record<string, unknown> & {
  sourceId: string;
  sourceRecordId: string;
  sourceVersion: string;
  caseNumber?: string;
  docketNumber?: string;
  title: string;
  caseType?: string;
  intellectualPropertyType?: string;
  institution?: string;
  procedure?: string;
  decisionDate?: string;
  outcomeExcerpt?: string;
  sourceRef?: string;
  dataMode: DataMode;
  documents: Array<{ versionId: string; state: string; ocrState?: string }>;
  annotations: Array<{ state: string }>;
};


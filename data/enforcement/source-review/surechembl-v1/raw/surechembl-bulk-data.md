> For the complete documentation index, see [llms.txt](https://chembl.gitbook.io/surechembl/llms.txt). Markdown versions of documentation pages are available by appending `.md` to page URLs; this page is available as [Markdown](https://chembl.gitbook.io/surechembl/downloads/bulk-data.md).

# Bulk data

The only way of downloading up-to-date SureChEMBL data.

Bulk data are machine-readable and allow any user to download the SureChEMBL data.

## Content

The bulk data offer access to the whole collection of SureChEMBL compounds with the patents in which they have been extracted from

<figure><img src="https://1396459327-files.gitbook.io/~/files/v0/b/gitbook-x-prod.appspot.com/o/spaces%2Fty0JOfWwPnEbs5wW271w%2Fuploads%2FTLyDqOiIK8fTjJaMYmXW%2Fbulk_data_diagram_20260210.png?alt=media&amp;token=c69b1519-335c-4866-b73d-71c0562ea446" alt=""><figcaption></figcaption></figure>

### compounds

All the compounds in SureChEMBL

| COLUMN\_NAME | DATA\_TYPE | COMMENT                                                   |
| ------------ | ---------- | --------------------------------------------------------- |
| id           | INT64      | Compound unique identifier (PK)                           |
| smiles       | STRING     | Canonical smiles, generated using RDKit                   |
| inchi        | STRING     | IUPAC standard InChI for the compound                     |
| inchi\_key   | STRING     | IUPAC standard InChI key for the compound                 |
| mol\_weight  | DOUBLE     | Molecular weight of the full compound including any salts |

### patents

All the patents containing compounds in SureChEMBL.

| COLUMN\_NAME      | DATA\_TYPE      | COMMENT                                                                                                                                                                                                                                                       |
| ----------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| id                | INT64           | Patent unique identifier (PK)                                                                                                                                                                                                                                 |
| patent\_number    | STRING          | Standardised format used to search system. Format: CC-PATNO-KK, e.g. WO-2011161255-A2                                                                                                                                                                         |
| country           | STRING          | Publication country                                                                                                                                                                                                                                           |
| publication\_date | DATE            | The date when the patent was first published                                                                                                                                                                                                                  |
| family\_id        | INT64           | Data retrieved from the EPO. Generally, simple families contain all records which share the same priority.  -1 if we have not received a family ID from DOCDB.                                                                                                |
| cpc               | LIST OF STRINGS | Cooperative Patent Classification ([full list](https://www.uspto.gov/web/patents/classification/cpc/html/cpc.html))                                                                                                                                           |
| ipcr              | LIST OF STRINGS | International Patent Classification Reform ([full list](https://www.wipo.int/en/web/classification-ipc))                                                                                                                                                      |
| ipc               | LIST OF STRINGS | International Patent Classification ([full list](https://www.wipo.int/en/web/classification-ipc))                                                                                                                                                             |
| ecla              | LIST OF STRINGS | European Classification (replaced by CPC in 2013)                                                                                                                                                                                                             |
| assignee          | LIST OF STRINGS | Assignee refers to the person or legal entity who owns the entire right, title, and interest in the application. Note that this field contains the assignee information provided by the publishing authority and in most cases doesn’t reflect reassignments. |
| title             | STRING          | Title of the document                                                                                                                                                                                                                                         |

### patent\_compound\_map

Patent-compound relationships with the patent field where the compound is found.

| COLUMN\_NAME | DATA\_TYPE | COMMENT                         |
| ------------ | ---------- | ------------------------------- |
| patent\_id   | INT64      | Patent unique identifier (FK)   |
| compound\_id | INT64      | Compound unique identifier (FK) |
| field\_id    | INT64      | Field unique identifier (FK)    |

### fields

patent field descriptions

| COLUMN\_NAME | DATA\_TYPE | COMMENT                                                                                                                                                                                               |
| ------------ | ---------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| id           | INT64      | Field unique identifier (PK)                                                                                                                                                                          |
| fieldname    | INT64      | <p>Patent field where information can be found<br>1 - Description<br>2 - Claims<br>3 - Abstract<br>4 - Title<br>5 - Image (for patents after 2007)<br>6 - MOL attachments (US patents after 2007)</p> |

### biomedical\_entities

All the non chemical annotation in SureChEMBL. We provide the captured terms, the corrected terms and the resolved form when the entity can be mapped to an ontology or controlled vocabulary.

| COLUMN\_NAME    | DATA\_TYPE | COMMENT                                                                                                                                 |
| --------------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| id              | INT64      | Biomedical entity unique identifer (PK)                                                                                                 |
| type\_id        | INT64      | Unique biomedical entity type identifier (FK)                                                                                           |
| original\_text  | STRING     | Text entity as it was captured in the patent                                                                                            |
| corrected\_text | STRING     | Corrected text                                                                                                                          |
| resolved\_form  | STRING     | <p>Identifier from a relevant data source based on the entity type.<br>1 - Uniprot & HGNC<br>2 - MeSH, Disease Ontology & Wikipedia</p> |

### biomedical\_locations

Biomedical entities-patent-field mapping, with the number of times the triplate occurs.

| COLUMN\_NAME | DATA\_TYPE | COMMENT                                 |
| ------------ | ---------- | --------------------------------------- |
| entity\_id   | INT64      | Biomedical entity unique identifer (FK) |
| patent\_id   | INT64      | Patent unique identifier (FK)           |
| field\_id    | INT64      | Field unique identifier (FK)            |
| count        | INT64      | Entity-Document-Field triplate count    |

### biomedical\_types

Types of biomedical entities that have been extracted.

| COLUMN\_NAME | DATA\_TYPE | COMMENT                                       |
| ------------ | ---------- | --------------------------------------------- |
| id           | INT64      | Unique biomedical entity type identifier (PK) |
| type\_name   | STRING     | Leadmine entity type name                     |
| description  | STRING     | Entity type description                       |

## Format

We produce the datasets in Parquet format that allows us to expose nested information in a machine-readable way.  Parquet is a columnar storage format widely used in big data platforms like Apache Spark and Hadoop. It’s designed for efficient querying, particularly for analytics that access only a subset of columns. Benefits include faster read performance, better compression, and support for complex nested data.

While interacting with Parquet files requires using a library (e.g., Pandas, PyArrow, Polars, DuckDB in Python), it’s very similar to querying a SQL database.

Note: we no longer provide an out-of-the-box solution for database creation (SureChEMBL data client no longer gets data update).

## Access

The parquet files are available [here](https://ftp.ebi.ac.uk/pub/databases/chembl/SureChEMBL/bulk_data/).

## Frequency of release

The bulk data are updated every 2 weeks with new parquet files containing the whole dataset. Because every release is independent from the previous one, the data schema might change to offer more data. In such case, the users will be notified.

## Usage

Examples (available soon) of how to use the bulk data are available in this [notebook](https://drive.google.com/file/d/1gGQrfyYsUE37CLhCPTkaXfRFrjOc984l/view?usp=sharing). Ultimately, the data can be loaded in a database.

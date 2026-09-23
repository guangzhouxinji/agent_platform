***************************************************************************
*
* Title:     SureChEMBL bulk data 
*
***************************************************************************

The data content in SureChEMBL is licensed under the highly permissive CC BY 4.0. See LICENCE file for details.

The bulk data offer access to the whole collection of SureChEMBL data, including compound and biomedical annotations extracted from the patent documents.

Each directory is a snapshot of the SureChEMBL database, generated every two weeks.

SureChEMBL automatically extracts chemistry from the full text patent documents provided by five patent authorities (WIPO, USPTO, EPO, JIPO and CNIPA). 
Compounds are derived from either chemical names found in text, images or attached MOL files, where available.
Biomedical entities are extracted using LeadMine (https://www.nextmovesoftware.com/leadmine.html). They focus on:
- Genes/Proteins
- Diseases
- Mechanisms

More details are available here: https://chembl.gitbook.io/surechembl/downloads/bulk-data 

Email us on surechembl-help<AT>ebi.ac.uk for questions and feedback.


Bulk data file description:
———————————————————————————

compounds.parquet				all the compounds in SureChEMBL

fields.parquet					patent field descriptions

fpsim2_fingerprints.h5				256 bit radius 2 morgan FPSim2 fingerprint
						database file of SureChEMBL compounds, 
						includes SureChEMBL compound id

patent_compound_mapping.parquet			each row contains a specific compound 
						that has been extracted from a specific 
						section of a specific patent document

patents.parquet					all the patents with compounds in SureChEMBL.

biomedical_entities.parquet			all the biomedical entities found the in SureChEmBL
						(GeneOrProtein, Disease, Mechanism). Generated with
						Leadmine

biomedical_locations.parquet			biomedical entities-patent-field mapping,
						with the number of times the triplate occurs

biomedical_types.parquet			types of biomedical entities that have been extracted

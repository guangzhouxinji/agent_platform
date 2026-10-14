-- Fail BEFORE dropping anything when derived history/counters exist. Never delete rows to reverse.
ALTER TABLE adj3_material_decision_version ADD CONSTRAINT ck_adj3_dec_reverse_empty CHECK(1=0);
ALTER TABLE adj3_material_decision_head ADD CONSTRAINT ck_adj3_dec_reverse_empty_head CHECK(organization_version=0);
DROP TABLE adj3_material_decision_version;
DROP TABLE adj3_material_decision_head;

-- Forward-only tightening; V102 and its recorded history remain unchanged.
-- Existing invalid bound rows cause ADD to fail; never repair their coordinates automatically.
ALTER TABLE con5_start_command ADD CONSTRAINT ck_con5_start_fence_present
 CHECK (attempt_id IS NULL OR fencing_token IS NOT NULL);

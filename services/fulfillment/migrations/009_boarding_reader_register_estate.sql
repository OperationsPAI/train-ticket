-- The readers this estate operates, stated in the register the service reads at
-- startup. The deployment that runs the estate stages them here, so a lane is
-- opened or closed by the migration that applies it rather than by a service
-- release. This estate boards from its station and provider lanes.
INSERT INTO boarding_reader_register (reader) VALUES ('STATION'), ('PROVIDER')
ON CONFLICT (reader) DO NOTHING;

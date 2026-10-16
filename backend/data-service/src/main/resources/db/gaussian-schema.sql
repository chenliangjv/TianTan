-- Reference schema for manual deployment. The current application uses
-- spring.jpa.hibernate.ddl-auto=update and creates these entities on startup.
CREATE TABLE IF NOT EXISTS gaussian_model (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    storage_key VARCHAR(36) NOT NULL UNIQUE,
    content_key VARCHAR(129) NOT NULL UNIQUE,
    source_splat_count BIGINT,
    total_splat_count BIGINT,
    chunk_count INT,
    rad_version INT,
    total_size BIGINT,
    bounds_json TEXT,
    sky_bounds_json TEXT,
    transform_json TEXT,
    initial_camera_json TEXT,
    sky_default_enabled BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS gaussian_asset (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_id BIGINT NOT NULL,
    role VARCHAR(10) NOT NULL,
    format VARCHAR(10) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    relative_path VARCHAR(300) NOT NULL,
    file_size BIGINT,
    sha256 VARCHAR(64) NOT NULL,
    splat_count BIGINT,
    CONSTRAINT uk_gaussian_asset_role UNIQUE(model_id, role),
    CONSTRAINT fk_gaussian_asset_model FOREIGN KEY(model_id) REFERENCES gaussian_model(id)
);

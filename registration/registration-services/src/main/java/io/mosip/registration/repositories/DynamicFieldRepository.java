package io.mosip.registration.repositories;

import io.mosip.kernel.core.dataaccess.spi.repository.BaseRepository;
import io.mosip.registration.entity.DynamicField;

public interface DynamicFieldRepository extends BaseRepository<DynamicField, String> {

	DynamicField findByIsActiveTrueAndNameAndLangCode(String fieldName, String langCode);
}

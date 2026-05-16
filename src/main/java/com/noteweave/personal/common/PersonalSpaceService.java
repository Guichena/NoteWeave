package com.noteweave.personal.common;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import com.noteweave.space.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PersonalSpaceService {

    private final SpaceRepository spaceRepository;

    public Space getRequiredPersonalSpace(Long userId) {
        return spaceRepository.findFirstByOwnerIdAndTypeAndStatusOrderByIdAsc(userId, SpaceType.PERSONAL, SpaceStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND, "Personal space not found"));
    }
}

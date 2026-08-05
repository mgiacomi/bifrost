package com.lokiscale.loomspan.internal.runtime.observation.catalog;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Optional;

public interface RegisteredSkillCatalog
{
    Optional<RegisteredSkillFile> find(String registeredName);

    List<RegisteredSkillFile.Summary> listAfter(@Nullable String exclusiveName, int limit);

    int registeredSkillCount();
}

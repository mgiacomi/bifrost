package com.lokiscale.bifrost.internal.runtime.observation.catalog;

import com.lokiscale.bifrost.internal.skill.YamlSkillCatalog;
import com.lokiscale.bifrost.internal.skill.YamlSkillDefinition;
import org.springframework.lang.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public final class DefaultRegisteredSkillCatalog implements RegisteredSkillCatalog
{
    private final NavigableMap<String, RegisteredSkillFile> entries;

    public DefaultRegisteredSkillCatalog(YamlSkillCatalog catalog)
    {
        this(Objects.requireNonNull(catalog, "catalog must not be null").getSkills(), new SkillSourcePathResolver());
    }

    public DefaultRegisteredSkillCatalog(
            Collection<YamlSkillDefinition> definitions,
            SkillSourcePathResolver pathResolver)
    {
        Objects.requireNonNull(definitions, "definitions must not be null");
        Objects.requireNonNull(pathResolver, "pathResolver must not be null");
        TreeMap<String, RegisteredSkillFile> built = new TreeMap<>();
        for (YamlSkillDefinition definition : definitions)
        {
            String name = definition.manifest().getName();
            RegisteredSkillFile file = new RegisteredSkillFile(
                    name,
                    pathResolver.resolve(definition.source()),
                    decode(definition.source().bytes(), name));
            if (built.putIfAbsent(name, file) != null)
            {
                throw new IllegalArgumentException("Duplicate registered skill name '" + name + "'");
            }
        }
        this.entries = java.util.Collections.unmodifiableNavigableMap(built);
    }

    @Override
    public Optional<RegisteredSkillFile> find(String registeredName)
    {
        Objects.requireNonNull(registeredName, "registeredName must not be null");
        return Optional.ofNullable(entries.get(registeredName));
    }

    @Override
    public List<RegisteredSkillFile.Summary> listAfter(@Nullable String exclusiveName, int limit)
    {
        if (limit <= 0)
        {
            throw new IllegalArgumentException("limit must be positive");
        }
        NavigableMap<String, RegisteredSkillFile> tail = exclusiveName == null
                ? entries
                : entries.tailMap(exclusiveName, false);
        return tail.values().stream().limit(limit).map(RegisteredSkillFile::summary).toList();
    }

    private static String decode(byte[] bytes, String name)
    {
        try
        {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
        catch (CharacterCodingException ex)
        {
            throw new IllegalStateException("YAML for registered skill '" + name + "' is not valid UTF-8", ex);
        }
    }
}

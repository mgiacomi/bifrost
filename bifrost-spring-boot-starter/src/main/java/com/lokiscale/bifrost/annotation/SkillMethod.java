package com.lokiscale.bifrost.annotation;

import com.lokiscale.bifrost.core.ModelPreference;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkillMethod
{
    String name() default "";

    String description();

    // ENG-005 keeps modelPreference as legacy Java-target metadata only.
    // YAML manifests remain the source of truth for LLM model execution settings.
    ModelPreference modelPreference() default ModelPreference.LIGHT;
}

package com.lokiscale.bifrost.annotation;

import com.lokiscale.bifrost.core.ModelPreference;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SkillMethod {

    String name() default "";

    String description();

    ModelPreference modelPreference() default ModelPreference.LIGHT;
}

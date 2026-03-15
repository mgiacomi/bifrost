package com.lokiscale.bifrost.annotation;

import com.lokiscale.bifrost.core.ModelPreference;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMethodTest {

    @Test
    void isRuntimeMethodAnnotation() {
        Retention retention = SkillMethod.class.getAnnotation(Retention.class);
        Target target = SkillMethod.class.getAnnotation(Target.class);

        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.METHOD);
    }

    @Test
    void exposesDefaultsAndConfiguredValues() throws NoSuchMethodException {
        SkillMethod defaultAnnotation = SampleSkills.class
                .getDeclaredMethod("defaultSkill")
                .getAnnotation(SkillMethod.class);
        SkillMethod configuredAnnotation = SampleSkills.class
                .getDeclaredMethod("heavySkill")
                .getAnnotation(SkillMethod.class);

        assertThat(defaultAnnotation).isNotNull();
        assertThat(defaultAnnotation.name()).isEmpty();
        assertThat(defaultAnnotation.description()).isEqualTo("Default route");
        assertThat(defaultAnnotation.modelPreference()).isEqualTo(ModelPreference.LIGHT);

        assertThat(configuredAnnotation).isNotNull();
        assertThat(configuredAnnotation.name()).isEqualTo("heavy.route");
        assertThat(configuredAnnotation.description()).isEqualTo("Heavy route");
        assertThat(configuredAnnotation.modelPreference()).isEqualTo(ModelPreference.HEAVY);
    }

    static class SampleSkills {

        @SkillMethod(description = "Default route")
        void defaultSkill() {
        }

        @SkillMethod(name = "heavy.route", description = "Heavy route", modelPreference = ModelPreference.HEAVY)
        void heavySkill() {
        }
    }
}

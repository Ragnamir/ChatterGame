package com.chattergame.web.user;

import jakarta.validation.constraints.Size;

/** Обновление профиля. `null` означает «не менять», пустая строка у почты — «убрать». */
public record UpdateProfileRequest(
        @Size(max = 128) String displayName, @Size(max = 320) String email, @Size(max = 16) String theme) {
}

package com.chattergame.web.chat;

import com.chattergame.game.Membership;

/**
 * Занятие персонажа с «пожеланиями по отыгрышу» (`REQUIREMENTS §17`, `PLAN` E14, задача 5).
 * Тело необязательно: отсутствие пожелания — законный случай, и требовать тело значило бы
 * превратить свободный текст игрока в обязанность.
 */
public record OccupationRequest(String roleplayWishes) {

    public static final int WISHES_MAX = Membership.ROLEPLAY_WISHES_MAX;
}

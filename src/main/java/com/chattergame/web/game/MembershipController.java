package com.chattergame.web.game;

import com.chattergame.game.GameMutationService;
import com.chattergame.game.GameQueryService;
import com.chattergame.game.Membership;
import com.chattergame.game.MembershipRepository;
import com.chattergame.game.MembershipRole;
import com.chattergame.user.AppUser;
import com.chattergame.user.AppUserRepository;
import com.chattergame.user.Logins;
import com.chattergame.util.ApiException;
import com.chattergame.web.AuthContext;
import com.chattergame.web.AuthContextResolver;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Участие: приглашения, вход, выход, бан (`PLAN` E13). Контроллер не пишет в доменные
 * таблицы: любая мутация идёт через {@link GameMutationService} (`D-034`), а сам он решает
 * только две задачи — разрешить логин в пользователя и проверить, кто смотрит состав.
 *
 * <p>Логин разрешается здесь, а не в агрегате игры: `game` не должен зависеть от учётных
 * записей, и `D-131` требует, чтобы несуществующий логин отвергался до всякой записи.
 */
@RestController
@RequestMapping("/games/{gameId}/memberships")
public class MembershipController {

    private final GameMutationService mutations;
    private final GameQueryService games;
    private final MembershipRepository memberships;
    private final AppUserRepository users;

    public MembershipController(
            GameMutationService mutations,
            GameQueryService games,
            MembershipRepository memberships,
            AppUserRepository users) {
        this.mutations = mutations;
        this.games = games;
        this.memberships = memberships;
        this.users = users;
    }

    /** Состав игры: мастеру — весь, участнику — только своё участие (`REQUIREMENTS §6`). */
    @GetMapping
    public List<MembershipView> list(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        games.requireReadable(auth.user().id(), gameId);
        Membership mine = memberships.find(gameId, auth.user().id())
                .orElseThrow(() -> ApiException.forbidden("not_a_member", "Нет доступа к этой игре"));
        List<Membership> visible = mine.role() == MembershipRole.MASTER
                ? memberships.findByGame(gameId)
                : List.of(mine);
        return visible.stream().map(MembershipView::of).toList();
    }

    /** Приглашение мастера (`D-131`). Повтор с тем же слотом идемпотентен. */
    @PostMapping("/invitations")
    public ResponseEntity<MembershipView> invite(
            @PathVariable UUID gameId, @Valid @RequestBody InvitationRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        Membership membership = mutations.invitePlayer(
                auth.user().id(),
                gameId,
                userIdByLogin(request.login()),
                parseRole(request.role()),
                request.characterId());
        return ResponseEntity.ok(MembershipView.of(membership));
    }

    /** Согласие приглашённого: слот занимается здесь и только здесь (`D-130`). */
    @PostMapping("/invitation/accept")
    public MembershipView accept(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return MembershipView.of(mutations.acceptInvitation(auth.user().id(), gameId));
    }

    /** Отказ от приглашения: `DECLINED`, а не выход — приглашённый не входил (`D-123`). */
    @PostMapping("/invitation/decline")
    public MembershipView decline(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return MembershipView.of(mutations.declineInvitation(auth.user().id(), gameId));
    }

    /** Свободный вход в публичную игру после `READY` (`D-132`). */
    @PostMapping("/join")
    public MembershipView join(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return MembershipView.of(mutations.joinOpenGame(auth.user().id(), gameId));
    }

    /** Добровольный выход: занятые слоты освобождаются, история персонажей остаётся. */
    @PostMapping("/leave")
    public MembershipView leave(@PathVariable UUID gameId, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return MembershipView.of(mutations.leaveGame(auth.user().id(), gameId));
    }

    /** Бан в одной игре (`D-133`): адресован участию, другие игры человека не трогает. */
    @PostMapping("/ban")
    public MembershipView ban(
            @PathVariable UUID gameId, @Valid @RequestBody MemberLoginRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return MembershipView.of(
                mutations.banPlayer(auth.user().id(), gameId, userIdByLogin(request.login())));
    }

    /** Снятие бана возвращает право входа, но не вводит человека в игру за него (`D-133`). */
    @PostMapping("/unban")
    public MembershipView unban(
            @PathVariable UUID gameId, @Valid @RequestBody MemberLoginRequest request, AuthContext context) {
        AuthContext auth = AuthContextResolver.require(context);
        return MembershipView.of(
                mutations.unbanPlayer(auth.user().id(), gameId, userIdByLogin(request.login())));
    }

    /** Роль приглашения: по умолчанию игрок; наблюдатель в приватную игру входит так же (`D-131`). */
    private static MembershipRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            return MembershipRole.PLAYER;
        }
        try {
            return MembershipRole.valueOf(role.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_role", "Неизвестная роль приглашения");
        }
    }

    /**
     * Логин в пользователя (`D-131`). Отказ `unknown_login` без создания строки: иначе
     * приглашение завело бы в составе игры человека, которого некому войти.
     */
    private UUID userIdByLogin(String login) {
        String normalized = Logins.normalize(login);
        return users.findByLoginNormalized(normalized)
                .filter(AppUser::isActive)
                .map(AppUser::id)
                .orElseThrow(() -> ApiException.badRequest(
                        "unknown_login", "Пользователь с таким логином не найден"));
    }
}

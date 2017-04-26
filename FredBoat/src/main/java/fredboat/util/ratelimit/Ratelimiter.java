package fredboat.util.ratelimit;

import fredboat.Config;
import fredboat.FredBoat;
import fredboat.audio.queue.PlaylistInfo;
import fredboat.command.maintenance.ShardsCommand;
import fredboat.command.music.control.SkipCommand;
import fredboat.commandmeta.abs.Command;
import fredboat.util.DiscordUtil;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.TextChannel;
import org.eclipse.jetty.util.ConcurrentHashSet;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by napster on 17.04.17.
 * <p>
 * this object should be threadsafe by itself
 * <p>
 * http://i.imgur.com/ha0R3XZ.gif
 * <p>
 * TODO: i18n here in other classes in this package and subpackages
 */
public class Ratelimiter {

    private static final int RATE_LIMIT_HITS_BEFORE_BLACKLIST = 10;


    //one ratelimiter for all running shards
    private static Ratelimiter ratelimiterSingleton;

    public static Ratelimiter getRatelimiter() {
        if (ratelimiterSingleton == null)
            ratelimiterSingleton = new Ratelimiter();

        return ratelimiterSingleton;
    }


    private final Set<Ratelimit> ratelimits;
    private Blacklist autoBlacklist = null;

    private Ratelimiter() {
        Set<Long> whitelist = new ConcurrentHashSet<>();

        //it is ok to use the jda of any shard as long as we aren't using it for guild specific stuff
        JDA jda = FredBoat.getFirstJDA();
        whitelist.add(Long.valueOf(DiscordUtil.getOwnerId(jda)));
        whitelist.add(jda.getSelfUser().getIdLong());
        //only works for those admins who are added with their userId and not through a roleId
        for (String admin : Config.CONFIG.getAdminIds())
            whitelist.add(Long.valueOf(admin));


        //Create all the rate limiters we want
        ratelimits = new HashSet<>();

        if (Config.CONFIG.useAutoBlacklist())
            autoBlacklist = new Blacklist(whitelist, RATE_LIMIT_HITS_BEFORE_BLACKLIST);

        ratelimits.add(new Ratelimit(whitelist, Ratelimit.Scope.USER, 5, 10000, Command.class));
        ratelimits.add(new Ratelimit(whitelist, Ratelimit.Scope.USER, 5, 20000, SkipCommand.class));
        ratelimits.add(new Ratelimit(whitelist, Ratelimit.Scope.USER, 2, 30000, ShardsCommand.class));

        ratelimits.add(new Ratelimit(whitelist, Ratelimit.Scope.GUILD, 10, 10000, Command.class));
        ratelimits.add(new Ratelimit(whitelist, Ratelimit.Scope.GUILD, 1000, 120000, PlaylistInfo.class));
    }

    /**
     * @param invoker           the user doing the request
     * @param command           the command or other kind of object to be used
     * @param weight            how heavy the request is, default should be 1
     * @param blacklistCallback a channel to write potential output from the auto blacklist. usually the channel the request was made in
     * @return a result object containing further information
     */
    public RateResult isAllowed(Member invoker, Object command, int weight, TextChannel blacklistCallback) {
        for (Ratelimit ratelimit : ratelimits) {
            if (ratelimit.getClazz().isInstance(command)) {
                RateResult result;
                //don't blacklist guilds
                if (ratelimit.scope == Ratelimit.Scope.GUILD) {
                    result = ratelimit.isAllowed(invoker, weight);
                } else {
                    result = ratelimit.isAllowed(invoker, weight, autoBlacklist, blacklistCallback);
                }
                if (!result.allowed) return result;
            }
        }
        return new RateResult(true, "Command is not ratelimited");
    }

    /**
     * @param id Id of the object whose blacklist status is to be checked, for example a userId or a guildId
     * @return true if the id is blacklisted, false if it's not
     */
    public boolean isBlacklisted(long id) {
        return autoBlacklist != null && autoBlacklist.isBlacklisted(id);
    }

    /**
     * Reset rate limits for the given id and removes it from the blacklist
     */
    public void liftLimitAndBlacklist(long id) {
        for (Ratelimit ratelimit : ratelimits) {
            ratelimit.liftLimit(id);
        }
        if (autoBlacklist != null)
            autoBlacklist.liftBlacklist(id);
    }
}

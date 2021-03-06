package be.huffle.mongoose;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.object.PermissionOverwrite;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Role;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.GuildChannel;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.rest.util.Color;
import discord4j.rest.util.Permission;
import discord4j.rest.util.PermissionSet;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;


public class Mongoose
{
	private String token;
	private Runnable runnable = () -> {};
	private boolean hasCompleted = true;

	public Mongoose(String token)
	{
		this.token = token;
	}

	public void run()
	{
		DiscordClient client = DiscordClient.create(token);
		client.withGateway(gatway ->
		{
			return Mono.when(gatway.on(VoiceStateUpdateEvent.class).doOnNext(this::onVoiceStateUpdate));
		}).block();
	}

	private void onVoiceStateUpdate(VoiceStateUpdateEvent event)
	{
		if (hasCompleted)
		{
			hasCompleted = false;
			Runnable thisRunnable = new VoiceStateUpdateRunnable(this, event);
			runnable = thisRunnable;
			thisRunnable.run();
		}
		else
		{
			Runnable thisRunnable = new VoiceStateUpdateRunnable(this, event);
			runnable = thisRunnable;
		}
	}

	public Mono<Void> updateChannels(VoiceStateUpdateEvent event)
	{
		Mono<Void> result = Mono.empty();

		Optional<Snowflake> channelSnowflake = event.getCurrent().getChannelId();
		Mono<VoiceChannel> voiceChannel = event.getCurrent().getChannel();
		VoiceState currentVoiceState = event.getCurrent();
		Guild guild = currentVoiceState.getGuild().block();
		User user = currentVoiceState.getUser().block();
		Member member = user.asMember(guild.getId()).block();
		Optional<Snowflake> categorySnowflake = event
				.getCurrent()
				.getChannel()
				.blockOptional()
				.flatMap(VoiceChannel::getCategoryId);

		event.getOld().ifPresent(voiceState ->
		{
			Mono<VoiceChannel> channel = voiceState.getChannel();
			result.and(channel);

			channel.subscribe(oldChannel ->
			{
				String oldChannelName = oldChannel.getName();
				if (oldChannelName.startsWith("Game"))
				{
					if (getMembersInVoiceChannel(oldChannel) == 0)
					{
						result.and(onChannelEmpty(oldChannel,guild));
					}
					else
					{
						result.and(onMemberLeaveGame(guild, oldChannelName, member));
					}
				}
			});
		});

		channelSnowflake.ifPresent(snowflake1 ->
		{
			System.out.println(user.getUsername() + " is connected to: " + channelSnowflake + "With name: " + voiceChannel.block().getName());
			String voiceChannelName = voiceChannel.block().getName();

			if (voiceChannelName.toLowerCase().equals("looking for game"))
			{
				result.and(onEnterLookingForGame(guild, categorySnowflake, member));
			}
			else if (voiceChannelName.toLowerCase().equals("create new game"))
			{
				result.and(onEnterCreateNewGame(guild, categorySnowflake, member));
			}
			else if (voiceChannelName.toLowerCase().startsWith("game"))
			{
				result.and(onEnterGame(guild, voiceChannelName, member));
			}
		});

		return result;
	}

	private Mono<Void> onEnterGame(Guild guild, String voiceChannelName, Member member)
	{
		Mono<Void> result = Mono.empty();

		Optional<Role> role = getRole(guild, voiceChannelName);
		role.ifPresent(r ->
		{
			Mono<Void> addRoleMember = member.addRole(r.getId());
			result.and(addRoleMember);
			addRoleMember.subscribe();
		});

		return result;
	}

	private Mono<Void> onEnterCreateNewGame(Guild guild, Optional<Snowflake> categorySnowflake, Member member)
	{
		Mono<Void> result = Mono.empty();

		VoiceChannel newChannel = createChannel(guild, categorySnowflake);
		Optional<Role> role = getRole(guild, newChannel.getName());
		role.ifPresent(r ->
		{
			Mono<Void> addRoleMember = member.addRole(r.getId());
			result.and(addRoleMember);
			addRoleMember.subscribe();
			result.and(moveMember(newChannel, member));
		});

		return result;
	}

	private Mono<Void> onEnterLookingForGame(Guild guild, Optional<Snowflake> categorySnowflake, Member member)
	{
		Mono<Void> result = Mono.empty();

		VoiceChannel availableChannel = getAvailableChannel(guild, categorySnowflake);
		Optional<Role> role = getRole(guild, availableChannel.getName());
		role.ifPresent(r ->
		{
			member.addRole(r.getId()).subscribe();
			result.and(moveMember(availableChannel, member));
		});

		return result;
	}

	private Mono<Void> onMemberLeaveGame(Guild guild, String oldChannelName, Member member)
	{
		Mono<Void> result = Mono.empty();
		Optional<Role> role = getRole(guild, oldChannelName);

		role.ifPresent(r ->
		{
			Mono<Void> deleteRoleMember = member.removeRole(r.getId());
			result.and(deleteRoleMember);
			deleteRoleMember.subscribe();
		});


		return result;
	}

	private Mono<Void> onChannelEmpty(VoiceChannel oldChannel, Guild guild)
	{
		Mono<Void> result = Mono.empty();
		String oldChannelName = oldChannel.getName();

		result.and(deleteTextChannel(oldChannelName, guild));
		Optional<Role> role = getRole(guild, oldChannelName);

		role.ifPresent(r ->
		{
			Mono<Void> deleteRole = r.delete();
			result.and(deleteRole);
			deleteRole.subscribe();
		});

		Mono<Void> deleteOldChannel = oldChannel.delete("The channel is empty");
		result.and(deleteOldChannel);
		deleteOldChannel.subscribe();

		return result;
	}

	private Mono<Void> deleteTextChannel(String name, Guild guild)
	{
		Mono<Void> result = Mono.empty();
		List<GuildChannel> guildChannels = guild.getChannels().collectList().block();
		String[] words = name.split(" ");
		String last = words[1];
		String channelName = "";

		for (GuildChannel guildChannel : guildChannels)
		{
			if (guildChannel.getType() == Channel.Type.GUILD_TEXT)
			{
				channelName = guildChannel.getName();
				if (channelName.equals("codes-and-talking-game-" + last))
				{
					Mono<Void> deleteChannel = guildChannel.delete("The channel is not needed anymore");
					result.and(deleteChannel);
					deleteChannel.subscribe();
				}
			}
		}
		return result;
	}

	private Optional<Role> getRole(Guild guild, String name)
	{
		List<Role> roles = guild.getRoles().collectList().block();
		String roleName = "";

		for (Role role : roles)
		{
			roleName = role.getName();
			if (roleName.equals(name))
			{
				return Optional.of(role);
			}
		}

		return Optional.empty();
	}

	private VoiceChannel getAvailableChannel(Guild guild, Optional<Snowflake> categorySnowflake)
	{
		int i = 1;
		Flux<GuildChannel> guildChannelFlux = guild.getChannels();
		List<GuildChannel> guildChannels = guildChannelFlux.collectList().block();
		String name = "";

		for (GuildChannel channel : guildChannels)
		{
			if (channel.getType() == Channel.Type.GUILD_VOICE)
			{
				name = channel.getName();
				if (name.startsWith("Game") && getMembersInVoiceChannel(channel) < ((VoiceChannel)channel).getUserLimit())
				{
					return (VoiceChannel)channel;
				}
			}
		}
		return createChannel(guild, categorySnowflake);
	}

	private long getMembersInVoiceChannel(GuildChannel channel)
	{
		return channel.getGuild().block().getMembers().filter(member ->
		{
			Optional<VoiceChannel> channelOptional = member
					.getVoiceState()
					.blockOptional()
					.flatMap(vs -> vs.getChannel().blockOptional());
			return channelOptional.map(vc -> vc.equals(channel)).orElse(false);
		}).count().block();
	}

	private Mono<Void> moveMember(VoiceChannel voiceChannel, Member member)
	{
		Mono<Void> editMember = member.edit(guildMemberEditSpec ->
		{
			guildMemberEditSpec.setNewVoiceChannel(voiceChannel.getId());
		});
		editMember.subscribe();
		return editMember;
	}

	private void createTextChannel(Guild guild, String name, Optional<Snowflake> categorySnowflake)
	{
		Role role = createRoleOnServer(guild, name);
		PermissionOverwrite overwriteRole = PermissionOverwrite.forRole(role.getId(), PermissionSet.of(Permission.VIEW_CHANNEL), PermissionSet.none());
		PermissionOverwrite overwriteEveryone = PermissionOverwrite.forRole(guild.getEveryoneRole().block().getId(),
				PermissionSet.none(), PermissionSet.of(Permission.VIEW_CHANNEL));
		HashSet<PermissionOverwrite> permissionOverwrites = new HashSet<>();
		permissionOverwrites.add(overwriteRole);
		permissionOverwrites.add(overwriteEveryone);
		GuildChannel guildChannel = guild.getChannels().blockFirst();
		int position = guildChannel.getRawPosition() + 1;

		createTextChannelWithName("codes-and-talking-" + name, categorySnowflake, position, permissionOverwrites, guild);
	}

	private void createTextChannelWithName(String name,
										   Optional<Snowflake> categorySnowflake, int position,
										   HashSet<PermissionOverwrite> permissionOverwrites,
										   Guild guild)
	{
		guild.createTextChannel(spec ->
		{
			spec.setName(name);
			categorySnowflake.ifPresent(snowflake ->
			{
				spec.setParentId(snowflake);
			});
			spec.setPosition(position);
			spec.setPermissionOverwrites(permissionOverwrites);
		}).subscribe();
	}

	private Role createRoleOnServer(Guild guild, String name)
	{
		return guild.createRole(spec ->
		{
			spec.setHoist(false);
			spec.setName(name);
			spec.setColor(Color.LIGHT_GRAY);
		}).block();
	}

	private VoiceChannel createChannel(Guild guild, Optional<Snowflake> categorySnowflake)
	{
		return guild.createVoiceChannel(spec ->
		{
			int i = 1;
			Flux<GuildChannel> guildChannelFlux = guild.getChannels();
			List<GuildChannel> guildChannels = guildChannelFlux.collectList().block();
			String channelName = "";
			String name = "";
			int position = 0;

			for(GuildChannel channel : guildChannels)
			{
				if (channel.getType() == Channel.Type.GUILD_VOICE)
				{
					name = channel.getName();
					if (name.startsWith("Game"))
					{
						if (!name.equals("Game " + i))
						{
							channelName = "Game " + i;
							position = channel.getRawPosition() - 1;
							break;
						}
						i++;
					}
				}
			}

			if (channelName.equals(""))
			{
				channelName = "Game " + i;
			}

			createTextChannel(guild, channelName, categorySnowflake);

			spec.setName(channelName);
			spec.setPosition(position);
			spec.setUserLimit(10);
			categorySnowflake.ifPresent(snowflake ->
			{
				spec.setParentId(snowflake);
			});
		}).block();
	}

	public Runnable getRunnable()
	{
		return runnable;
	}

	public void setRunnable(Runnable runnable)
	{
		this.runnable = runnable;
	}

	public boolean isHasCompleted()
	{
		return hasCompleted;
	}

	public void setHasCompleted(boolean hasCompleted)
	{
		this.hasCompleted = hasCompleted;
	}
}

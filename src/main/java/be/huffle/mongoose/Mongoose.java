package be.huffle.mongoose;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.object.PermissionOverwrite;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.channel.Category;
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

	public Mongoose(String token)
	{
		this.token = token;
	}

	public void run()
	{
		DiscordClient client = DiscordClient.create(token);
		client.withGateway(gatway ->
		{
			return Mono.when(gatway.on(VoiceStateUpdateEvent.class).doOnNext(Mongoose::onVoiceStateUpdate));
		}).block();
	}

	private static void onVoiceStateUpdate(VoiceStateUpdateEvent event)
	{
		Optional<Snowflake> channelSnowflake = event.getCurrent().getChannelId();
		Mono<VoiceChannel> voiceChannel = event.getCurrent().getChannel();
		Guild guild = event.getCurrent().getGuild().block();
		Member member = event.getCurrent().getUser().block().asMember(guild.getId()).block();
		Optional<Snowflake> categorySnowflake = event
				.getCurrent()
				.getChannel()
				.blockOptional()
				.flatMap(VoiceChannel::getCategoryId);

		event.getOld().flatMap(voiceState -> voiceState.getChannel().blockOptional()).ifPresent(oldChannel ->
		{
			if (oldChannel.getName().startsWith("Game"))
			{
				if (getMembersInVoiceChannel(oldChannel) == 0)
				{
					deleteTextChannel(oldChannel.getName(), guild);
					Role role = getRole(guild, oldChannel.getName());
					if (role != null)
					{
						role.delete().subscribe();
					}
					oldChannel.delete("The channel is empty").subscribe();
				}
				else
				{
					Role role = getRole(guild, oldChannel.getName());
					member.removeRole(role.getId()).subscribe();
				}
			}
		});

		channelSnowflake.ifPresent(snowflake1 ->
		{
			System.out.println("User is connected to: " + channelSnowflake + "With name: " + voiceChannel.block().getName());

			String voiceChannelName = voiceChannel.block().getName();
			if (voiceChannelName.toLowerCase().equals("looking for game"))
			{
				VoiceChannel availableChannel = getAvailableChannel(guild, categorySnowflake);
				Role role = getRole(guild, availableChannel.getName());
				member.addRole(role.getId()).subscribe();
				moveMember(availableChannel, member);
			}
			else if (voiceChannelName.toLowerCase().equals("create new game"))
			{
				VoiceChannel newChannel = createChannel(guild, categorySnowflake);
				Role role = getRole(guild, newChannel.getName());
				member.addRole(role.getId()).subscribe();
				moveMember(newChannel, member);
			}
			else if (voiceChannelName.toLowerCase().startsWith("game"))
			{
				Role role = getRole(guild, voiceChannelName);
				member.addRole(role.getId()).subscribe();
			}
		});
	}

	private static void deleteTextChannel(String name, Guild guild)
	{
		List<GuildChannel> guildChannels = guild.getChannels().collectList().block();
		String[] words = name.split(" ");
		String last = words[1];
		String channelName = "";

		for (GuildChannel guildChannel : guildChannels)
		{
			if (guildChannel.getType() == Channel.Type.GUILD_TEXT)
			{
				channelName = guildChannel.getName();
				if (channelName.equals("codes-game-" + last))
				{
					guildChannel.delete("The channel is not needed anymore").subscribe();
				}
			}
		}


	}

	private static Role getRole(Guild guild, String name)
	{
		List<Role> roles = guild.getRoles().collectList().block();
		String roleName = "";

		for (Role role : roles)
		{
			roleName = role.getName();
			if (roleName.equals(name))
			{
				return role;
			}
		}

		return null;
	}

	private static VoiceChannel getAvailableChannel(Guild guild, Optional<Snowflake> categorySnowflake)
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

	private static long getMembersInVoiceChannel(GuildChannel channel)
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

	private static void moveMember(VoiceChannel voiceChannel, Member member)
	{
		member.edit(guildMemberEditSpec ->
		{
			guildMemberEditSpec.setNewVoiceChannel(voiceChannel.getId());
		}).subscribe();
	}

	private static void createTextChannel(Guild guild, String name, Optional<Snowflake> categorySnowflake)
	{
		Role role = createRoleOnServer(guild, name);
		PermissionOverwrite overwriteRole = PermissionOverwrite.forRole(role.getId(), PermissionSet.of(Permission.VIEW_CHANNEL), PermissionSet.none());
		PermissionOverwrite overwriteEveryone = PermissionOverwrite.forRole(guild.getEveryoneRole().block().getId(),
				PermissionSet.none(), PermissionSet.of(Permission.VIEW_CHANNEL));
		HashSet<PermissionOverwrite> permissionOverwrites = new HashSet<>();
		permissionOverwrites.add(overwriteRole);
		permissionOverwrites.add(overwriteEveryone);

		guild.createTextChannel(spec ->
		{
			GuildChannel guildChannel = guild.getChannels().blockFirst();
			int position = guildChannel.getRawPosition() + 1;

			spec.setName("codes-" + name);
			categorySnowflake.ifPresent(snowflake ->
			{
				spec.setParentId(snowflake);
			});
			spec.setPosition(position);
			spec.setPermissionOverwrites(permissionOverwrites);
		}).subscribe();
	}

	private static Role createRoleOnServer(Guild guild, String name)
	{
		return guild.createRole(spec ->
		{
			spec.setHoist(false);
			spec.setName(name);
			spec.setColor(Color.LIGHT_GRAY);
		}).block();
	}

	private static VoiceChannel createChannel(Guild guild, Optional<Snowflake> categorySnowflake)
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
}

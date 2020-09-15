package be.huffle.mongoose;

import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.object.entity.*;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.GuildChannel;
import discord4j.core.object.entity.channel.VoiceChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;


public class Mongoose
{
	private String token;
	private static Member currentMember;

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
		Optional<Snowflake> categorySnowflake = event.getCurrent().getChannel().block().getCategoryId();

		event.getOld().flatMap(voiceState -> voiceState.getChannel().blockOptional()).ifPresent(oldChannel ->
		{
			if (oldChannel.getName().startsWith("Game") && getMembersInVoiceChannel(oldChannel) == 0)
			{
				oldChannel.delete("The channel is empty").subscribe();
			}
		});

		channelSnowflake.ifPresent(snowflake1 ->
		{
			System.out.println("User is connected to: " + channelSnowflake + "With name: " + voiceChannel.block().getName());

			String voiceChannelName = voiceChannel.block().getName().toLowerCase();
			if (voiceChannelName.equals("looking for game"))
			{
				VoiceChannel availableChannel = getAvailableChannel(guild, categorySnowflake);
				moveMember(availableChannel, member);
			}
			else if (voiceChannelName.equals("create new game"))
			{
				VoiceChannel newChannel = createChannel(guild, categorySnowflake);
				moveMember(newChannel, member);
			}
		});
	}

	private static VoiceChannel getAvailableChannel(Guild guild, Optional<Snowflake> categorySnowflake)
	{
		int i = 1;
		Flux<GuildChannel> guildChannelFlux = guild.getChannels();
		List<GuildChannel> guildChannels = guildChannelFlux.collectList().block();
		String name = "";

		for(GuildChannel channel : guildChannels)
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

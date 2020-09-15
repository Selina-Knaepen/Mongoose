package be.huffle.mongoose;

import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.*;
import discord4j.core.object.util.Snowflake;
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
		DiscordClient client = new DiscordClientBuilder(token).build();
		client.getEventDispatcher().on(MessageCreateEvent.class).subscribe(Mongoose::onMessageReceived);
		client.getEventDispatcher().on(VoiceStateUpdateEvent.class).subscribe(Mongoose::onVoiceStateUpdate);
		client.login().block();
	}

	private static void onVoiceStateUpdate(VoiceStateUpdateEvent event)
	{
		Optional<Snowflake> snowflake = event.getCurrent().getChannelId();
		Mono<VoiceChannel> voiceChannel = event.getCurrent().getChannel();
		Guild guild = event.getCurrent().getGuild().block();
		Member member = event.getCurrent().getUser().block().asMember(guild.getId()).block();

		event.getOld().flatMap(voiceState -> voiceState.getChannel().blockOptional()).ifPresent(oldChannel ->
		{
			if (oldChannel.getName().startsWith("Game") && getMembersInVoiceChannel(oldChannel) == 0)
			{
				oldChannel.delete("The channel is empty").subscribe();
			}
		});

		snowflake.ifPresent(snowflake1 ->
		{
			System.out.println("User is connected to: " + snowflake + "With name: " + voiceChannel.block().getName());

			if (voiceChannel.block().getName().toLowerCase().equals("looking for game"))
			{
				VoiceChannel availableChannel = getAvailableChannel(guild);
				moveMember(availableChannel, member);
			}
		});
	}

	private static VoiceChannel getAvailableChannel(Guild guild)
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
		return createChannel(guild);
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

	private static VoiceChannel createChannel(Guild guild)
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

		}).block();
	}

	private static void onMessageReceived(MessageCreateEvent event)
	{
		currentMember = null;
		if (!event.getMember().isPresent())
		{
			return;
		}

		if (event.getMember().get().isBot())
		{
			return;
		}

		currentMember = event.getMember().get();
		event.getMessage().getChannel().subscribe(channel -> onMessageReceivedOnChannel(event.getMessage(), channel));
	}

	private static void onMessageReceivedOnChannel(Message message, MessageChannel channel)
	{
		message.getContent().ifPresent(content ->
		{
			if (content.toLowerCase().startsWith("mongoose"))
			{
				channel.createMessage("Hello I am A Mongoose").subscribe();
			}
		});
	}
}

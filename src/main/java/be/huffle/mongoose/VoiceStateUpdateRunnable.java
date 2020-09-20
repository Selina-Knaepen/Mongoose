package be.huffle.mongoose;

import discord4j.core.event.domain.VoiceStateUpdateEvent;
import reactor.core.publisher.Mono;

public class VoiceStateUpdateRunnable implements Runnable
{
	private Mongoose mongoose;
	private VoiceStateUpdateEvent event;

	public VoiceStateUpdateRunnable(Mongoose mongoose, VoiceStateUpdateEvent event)
	{
		this.mongoose = mongoose;
		this.event = event;
	}

	@Override
	public void run()
	{
		Mono<Void> mongooseMono = mongoose.updateChannels(event);
		mongooseMono
				.doFinally(signal ->
				{
					if (mongoose.getRunnable() == this)
					{
						mongoose.setHasCompleted(true);
					}
					else
					{
						mongoose.getRunnable().run();
					}
				})
				.subscribe();
	}
}

package integration;

import fm.bootifulpodcast.podbean.Episode;
import fm.bootifulpodcast.podbean.EpisodeStatus;
import fm.bootifulpodcast.podbean.EpisodeType;
import fm.bootifulpodcast.podbean.PodbeanClient;
import integration.aws.AwsS3Service;
import integration.database.Podcast;
import integration.database.PodcastRepository;
import integration.events.PodcastPublishedToPodbeanEvent;
import integration.utils.FileUtils;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.MessageChannels;
import org.springframework.integration.handler.GenericHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.FileOutputStream;

/**
 * This is step 3 in the flow.
 *
 * @author <a href="mailto:josh@joshlong.com">Josh Long</a>
 */
@Log4j2
@Configuration
class PodbeanIntegrationConfiguration {

	@Bean
	IntegrationFlow podbeanPublicationPipeline(ApplicationEventPublisher publisher,
			AwsS3Service s3Service, ConnectionFactory connectionFactory,
			PodcastRepository repository, PodbeanClient podbeanClient,
			PipelineProperties pipelineProperties) {
		var amqpInboundAdapter = Amqp //
				.inboundAdapter(connectionFactory,
						pipelineProperties.getPodbean().getRequestsQueue()) //
				.get();
		var podbeanDirectory = pipelineProperties.getPodbean().getPodbeanDirectory();
		FileUtils.ensureDirectoryExists(podbeanDirectory);
		return IntegrationFlows//
				.from(amqpInboundAdapter)//
				.transform(incoming -> repository.findByUid((String) incoming).get())
				.handle((GenericHandler<Podcast>) (podcast, messageHeaders) -> {
					var fileForDownloadedMp3 = new File(podbeanDirectory,
							podcast.getUid() + ".mp3");
					this.downloadPodcastMp3ToLocalFileSystem(s3Service, podcast,
							fileForDownloadedMp3);
					var upload = podbeanClient.upload(
							MediaType.parseMediaType("audio/mpeg"), fileForDownloadedMp3,
							fileForDownloadedMp3.length());
					var episode = podbeanClient.publishEpisode(podcast.getTitle(),
							podcast.getDescription(), EpisodeStatus.DRAFT,
							EpisodeType.PUBLIC, upload.getFileKey(), null);
					publisher.publishEvent(
							new PodcastPublishedToPodbeanEvent(podcast.getUid(),
									episode.getMediaUrl(), episode.getPlayerUrl()));
					log.info("the episode has been published to " + episode.toString());
					return null;
				})//
				.get();
	}

	@SneakyThrows
	private void downloadPodcastMp3ToLocalFileSystem(AwsS3Service s3Service,
			Podcast podcast, File file) {
		log.info("trying to download the S3 file for podcast " + podcast.getUid()
				+ " and publish it to the Podbean API.");
		var s3Key = podcast.getS3OutputFileName();
		var s3Object = s3Service.downloadOutputFile(s3Key);
		FileCopyUtils.copy(s3Object.getObjectContent(), new FileOutputStream(file));
		Assert.isTrue(file.exists() && file.length() > 0,
				"the file could not be downloaded to " + file.getAbsolutePath() + ".");
	}

	@Bean
	MessageChannel podbeanPublicationChannel() {
		return MessageChannels.direct().get();
	}

}
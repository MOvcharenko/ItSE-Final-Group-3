package persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<NoticeEntity, String> {

	List<NoticeEntity> findByRecipientIdOrderByCreatedAtDesc(String recipientId);

	Optional<NoticeEntity> findByIdAndRecipientId(String id, String recipientId);
}
